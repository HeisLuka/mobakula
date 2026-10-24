//! S4d3: crash-durable, source-scoped document identity and import intent.
//! No S3 caller authority, File Gate writer, or automatic recovery writes.
//! Prepared is NOT a filesystem reservation; uncertain operations fail HOLD.

use aquilum_core::sync::{DocumentId, RevisionId};
use rusqlite::{params, Connection, OpenFlags, OptionalExtension, TransactionBehavior};
use std::{fs, path::{Path, PathBuf}, time::Duration};
use uuid::Uuid;

const DB_NAME: &str = "s3-receive-map-v1.sqlite";
const MAX_ROWS: usize = 10_000;

#[derive(Debug)]
pub enum S3BindingError {
    InvalidRoot, InvalidInput, UnsafeDatabase, Database(rusqlite::Error),
    Conflict, TooMany, Corrupt,
}
impl From<rusqlite::Error> for S3BindingError {
    fn from(e: rusqlite::Error) -> Self { Self::Database(e) }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum S3BindingPhase { Prepared, Bound, Hold }
impl S3BindingPhase {
    fn text(self) -> &'static str {
        match self { Self::Prepared => "prepared", Self::Bound => "bound", Self::Hold => "hold" }
    }
    fn parse(s: &str) -> Result<Self, S3BindingError> {
        match s { "prepared" => Ok(Self::Prepared),
            "bound" => Ok(Self::Bound), "hold" => Ok(Self::Hold),
            _ => Err(S3BindingError::Corrupt) }
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct S3Binding {
    pub source_scope: String,
    pub document_id: DocumentId,
    pub local_note_id: Uuid,
    pub revision_id: RevisionId,
    pub blob_blake3: String,
    pub phase: S3BindingPhase,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum S3BindingLookup {
    Unmapped,
    Prepared,
    Bound,
    Hold,
}
impl From<S3BindingPhase> for S3BindingLookup {
    fn from(s: S3BindingPhase) -> Self {
        match s { S3BindingPhase::Prepared => Self::Prepared,
            S3BindingPhase::Bound => Self::Bound, S3BindingPhase::Hold => Self::Hold }
    }
}

/// Only an Android app-private directory created/owned by NoteVault is valid.
/// Callers may never supply an S3 filename or untrusted remote path.
fn database_path(app_root: &Path) -> Result<PathBuf, S3BindingError> {
    if !app_root.is_absolute() { return Err(S3BindingError::InvalidRoot); }
    let root = fs::canonicalize(app_root).map_err(|_| S3BindingError::InvalidRoot)?;
    if root != app_root { return Err(S3BindingError::InvalidRoot); }
    let core_dir = root.join("core");
    let meta = fs::symlink_metadata(&core_dir).map_err(|_| S3BindingError::InvalidRoot)?;
    if !meta.is_dir() || meta.file_type().is_symlink() {
        return Err(S3BindingError::InvalidRoot);
    }
    Ok(core_dir.join(DB_NAME))
}
fn checked_db_file(path: &Path) -> Result<bool, S3BindingError> {
    match fs::symlink_metadata(path) {
        Ok(meta) if meta.is_file() && !meta.file_type().is_symlink() => Ok(true),
        Ok(_) => Err(S3BindingError::UnsafeDatabase),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(_) => Err(S3BindingError::UnsafeDatabase),
    }
}
fn valid_digest(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn validate_key(scope: &str, hash: &str) -> Result<(), S3BindingError> {
    if !valid_digest(scope) || !valid_digest(hash) {
        Err(S3BindingError::InvalidInput)
    } else { Ok(()) }
}
fn row_to_binding(row: &rusqlite::Row<'_>) -> Result<S3Binding, rusqlite::Error> {
    let scope: String = row.get(0)?;
    let doc: String = row.get(1)?;
    let local: String = row.get(2)?;
    let rev: String = row.get(3)?;
    let hash: String = row.get(4)?;
    let phase: String = row.get(5)?;
    let parsed = (|| {
        if !valid_digest(&scope) || !valid_digest(&hash) { return None; }
        let document_id: DocumentId = doc.parse().ok()?;
        let local_note_id: Uuid = local.parse().ok()?;
        let revision_id: RevisionId = rev.parse().ok()?;
        let phase = S3BindingPhase::parse(&phase).ok()?;
        if local_note_id.to_string() != local || document_id.to_string() != doc
            || revision_id.to_string() != rev { return None; }
        Some(S3Binding { source_scope: scope, document_id, local_note_id,
            revision_id, blob_blake3: hash, phase })
    })();
    parsed.ok_or_else(|| rusqlite::Error::InvalidQuery)
}
fn query_binding(
    db: &Connection, scope: &str, document_id: DocumentId,
) -> Result<Option<S3Binding>, S3BindingError> {
    db.query_row(
        "SELECT source_scope,document_id,local_note_id,revision_id,blob_blake3,phase
         FROM receive_bindings WHERE source_scope=?1 AND document_id=?2",
        params![scope, document_id.to_string()], row_to_binding,
    ).optional().map_err(|e| match e {
        rusqlite::Error::InvalidQuery => S3BindingError::Corrupt,
        other => S3BindingError::Database(other),
    })
}

pub struct S3BindingJournal { db: Connection }
impl S3BindingJournal {
    /// Only call for a locally approved, explicit import workflow.
    /// Creates a private journal but NEVER creates a note.
    pub fn open(app_root: &Path) -> Result<Self, S3BindingError> {
        let path = database_path(app_root)?;
        let previously_created = checked_db_file(&path)?;
        let db = Connection::open_with_flags(&path,
            OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE)?;
        db.busy_timeout(Duration::from_secs(3))?;
        if previously_created {
            let version: i64 = db.pragma_query_value(None, "user_version", |r| r.get(0))?;
            if version != 1 { return Err(S3BindingError::Corrupt); }
        }
        db.execute_batch(
            "PRAGMA journal_mode=WAL;
             PRAGMA synchronous=FULL;
             PRAGMA foreign_keys=ON;
             CREATE TABLE IF NOT EXISTS receive_bindings (
                 source_scope TEXT NOT NULL CHECK(length(source_scope)=64),
                 document_id TEXT NOT NULL,
                 local_note_id TEXT NOT NULL UNIQUE,
                 revision_id TEXT NOT NULL,
                 blob_blake3 TEXT NOT NULL CHECK(length(blob_blake3)=64),
                 phase TEXT NOT NULL CHECK(phase IN ('prepared','bound','hold')),
                 PRIMARY KEY (source_scope, document_id)
             );
             PRAGMA user_version=1;",
        )?;
        Ok(Self { db })
    }

    /// Read-only inspection for the S3 probe. A first-run scan does not
    /// initialize or create a journal or mistake its absence for a mapping.
    pub fn open_existing(app_root: &Path) -> Result<Option<Self>, S3BindingError> {
        let path = database_path(app_root)?;
        if !checked_db_file(&path)? { return Ok(None); }
        let db = Connection::open_with_flags(&path, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
        let version: i64 = db.pragma_query_value(None, "user_version", |r| r.get(0))?;
        if version != 1 { return Err(S3BindingError::Corrupt); }
        Ok(Some(Self { db }))
    }

    pub fn lookup(
        &self, scope: &str, document_id: DocumentId,
    ) -> Result<Option<S3Binding>, S3BindingError> {
        if !valid_digest(scope) { return Err(S3BindingError::InvalidInput); }
        query_binding(&self.db, scope, document_id)
    }

    /// Durable *intent* only. It does not reserve or create a file.
    /// Retrying the identical intent after a crash returns exactly the same
    /// local UUID. A changed revision/content or a bound/hold row is conflict.
    pub fn prepare(
        &mut self, scope: &str, document_id: DocumentId,
        revision_id: RevisionId, blob_blake3: &str,
    ) -> Result<S3Binding, S3BindingError> {
        self.prepare_internal(scope, document_id, revision_id, blob_blake3, true)
    }

    /// Only a fresh intent is allowed to reach File Gate. Recovered PREPARED
    /// (even with exactly matching bytes) is HOLD, never a silent retry.
    pub(crate) fn prepare_fresh(
        &mut self, scope: &str, document_id: DocumentId,
        revision_id: RevisionId, blob_blake3: &str,
    ) -> Result<S3Binding, S3BindingError> {
        self.prepare_internal(scope, document_id, revision_id, blob_blake3, false)
    }

    fn prepare_internal(
        &mut self, scope: &str, document_id: DocumentId,
        revision_id: RevisionId, blob_blake3: &str, idempotent: bool,
    ) -> Result<S3Binding, S3BindingError> {
        validate_key(scope, blob_blake3)?;
        let tx = self.db.transaction_with_behavior(TransactionBehavior::Immediate)?;
        if let Some(existing) = query_binding(&tx, scope, document_id)? {
            return if idempotent && existing.phase == S3BindingPhase::Prepared
                && existing.revision_id == revision_id
                && existing.blob_blake3 == blob_blake3
            {
                Ok(existing)
            } else { Err(S3BindingError::Conflict) };
        }
        let rows: i64 = tx.query_row(
            "SELECT COUNT(*) FROM receive_bindings", [], |r| r.get(0)
        )?;
        if rows >= MAX_ROWS as i64 { return Err(S3BindingError::TooMany); }
        let local = Uuid::new_v4();
        tx.execute(
            "INSERT INTO receive_bindings
             (source_scope,document_id,local_note_id,revision_id,blob_blake3,phase)
             VALUES (?1,?2,?3,?4,?5,'prepared')",
            params![scope, document_id.to_string(), local.to_string(),
                revision_id.to_string(), blob_blake3],
        ).map_err(|e| match e {
            rusqlite::Error::SqliteFailure(ref code, _)
                if code.code == rusqlite::ErrorCode::ConstraintViolation =>
                    S3BindingError::Conflict,
            other => S3BindingError::Database(other),
        })?;
        tx.commit()?;
        Ok(S3Binding {
            source_scope: scope.to_owned(), document_id, local_note_id: local,
            revision_id, blob_blake3: blob_blake3.to_owned(),
            phase: S3BindingPhase::Prepared,
        })
    }

    /// The only durable confirmation path: caller must have just completed
    /// File Gate create, HistoryService current-version check and disk readback.
    /// DB UPDATE is conditional on the EXACT prepared identity and digest.
    pub(crate) fn confirm_with_receipt(
        &mut self, prepared: &S3Binding, observed_local_id: Uuid,
        observed_blake3: &str,
    ) -> Result<(), S3BindingError> {
        validate_key(&prepared.source_scope, &prepared.blob_blake3)?;
        if observed_local_id != prepared.local_note_id
            || observed_blake3 != prepared.blob_blake3
            || prepared.phase != S3BindingPhase::Prepared
        {
            return Err(S3BindingError::Conflict);
        }
        let tx = self.db.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let changed = tx.execute(
            "UPDATE receive_bindings SET phase='bound'
             WHERE source_scope=?1 AND document_id=?2 AND local_note_id=?3
               AND revision_id=?4 AND blob_blake3=?5 AND phase='prepared'",
            params![prepared.source_scope, prepared.document_id.to_string(),
                prepared.local_note_id.to_string(), prepared.revision_id.to_string(),
                prepared.blob_blake3],
        )?;
        if changed != 1 { return Err(S3BindingError::Conflict); }
        tx.commit()?;
        Ok(())
    }

    /// Uncertain/recovered states stay permanently on HOLD until a separate
    /// future explicit, authenticated File Gate recovery path is implemented.
    /// No duplicate create, no automatic promotion based on matching bytes.
    pub fn hold(
        &mut self, scope: &str, document_id: DocumentId,
    ) -> Result<(), S3BindingError> {
        if !valid_digest(scope) { return Err(S3BindingError::InvalidInput); }
        let tx = self.db.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let existing = query_binding(&tx, scope, document_id)?
            .ok_or(S3BindingError::Conflict)?;
        if existing.phase == S3BindingPhase::Bound {
            return Err(S3BindingError::Conflict);
        }
        tx.execute(
            "UPDATE receive_bindings SET phase='hold'
             WHERE source_scope=?1 AND document_id=?2",
            params![scope, document_id.to_string()],
        )?;
        tx.commit()?;
        Ok(())
    }

    /// Does not promote Prepared to Bound. This must be added only when the
    /// trusted S4d4 File Gate writer can provide its own verified receipt.
    pub fn status(&self, scope: &str, doc: DocumentId)
        -> Result<S3BindingLookup, S3BindingError>
    {
        Ok(self.lookup(scope, doc)?.map_or(
            S3BindingLookup::Unmapped, |b| b.phase.into()
        ))
    }
}

/// Intentionally no public bind/confirm function. Neither a hash match nor
/// reboot recovery constitutes an import receipt or publisher authorization.

#[cfg(test)]
mod tests {
    use super::*;

    fn root() -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        fs::create_dir(dir.path().join("core")).unwrap();
        dir
    }
    fn fixture() -> (String, DocumentId, RevisionId, String) {
        ("a".repeat(64), DocumentId::new(), RevisionId::new(), "b".repeat(64))
    }
    #[test]
    fn restart_keeps_one_pending_identity_and_never_promotes_to_bound() {
        let dir = root();
        let (scope, doc, revision, hash) = fixture();
        let pending = {
            let mut db = S3BindingJournal::open(dir.path()).unwrap();
            assert_eq!(db.status(&scope, doc).unwrap(), S3BindingLookup::Unmapped);
            let first = db.prepare(&scope, doc, revision, &hash).unwrap();
            assert_eq!(db.prepare(&scope, doc, revision, &hash).unwrap(), first);
            assert_eq!(first.phase, S3BindingPhase::Prepared);
            first
        };
        let mut db = S3BindingJournal::open(dir.path()).unwrap();
        let resumed = db.prepare(&scope, doc, revision, &hash).unwrap();
        assert_eq!(resumed.local_note_id, pending.local_note_id);
        assert_eq!(db.status(&scope, doc).unwrap(), S3BindingLookup::Prepared);
        assert!(matches!(db.prepare(&scope, doc, RevisionId::new(), &hash),
            Err(S3BindingError::Conflict)));
        db.hold(&scope, doc).unwrap();
        drop(db);
        let mut db = S3BindingJournal::open(dir.path()).unwrap();
        assert_eq!(db.status(&scope, doc).unwrap(), S3BindingLookup::Hold);
        assert!(matches!(db.prepare(&scope, doc, revision, &hash),
            Err(S3BindingError::Conflict)));
    }

    #[test]
    fn fresh_receive_does_not_retry_an_interrupted_prepared_row() {
        let dir = root();
        let (scope, doc, revision, hash) = fixture();
        let intent = {
            let mut db = S3BindingJournal::open(dir.path()).unwrap();
            let row = db.prepare_fresh(&scope, doc, revision, &hash).unwrap();
            assert!(matches!(db.prepare_fresh(&scope, doc, revision, &hash),
                Err(S3BindingError::Conflict)));
            row
        };
        let mut db = S3BindingJournal::open(dir.path()).unwrap();
        assert!(matches!(db.prepare_fresh(&scope, doc, revision, &hash),
            Err(S3BindingError::Conflict)));
        assert!(matches!(db.confirm_with_receipt(&intent, Uuid::new_v4(), &hash),
            Err(S3BindingError::Conflict)));
        assert_eq!(db.status(&scope, doc).unwrap(), S3BindingLookup::Prepared);
        db.hold(&scope, doc).unwrap();
        assert_eq!(db.status(&scope, doc).unwrap(), S3BindingLookup::Hold);
    }

    #[test]
    fn source_scopes_are_independent_but_local_ids_unique() {
        let dir = root();
        let (scope, doc, rev, hash) = fixture();
        let mut db = S3BindingJournal::open(dir.path()).unwrap();
        let a = db.prepare(&scope, doc, rev, &hash).unwrap();
        let b = db.prepare(&"c".repeat(64), doc, rev, &hash).unwrap();
        assert_ne!(a.local_note_id, b.local_note_id);
        assert_eq!(a.document_id, b.document_id);
        assert_eq!(a.source_scope, scope);
        assert!(matches!(db.prepare("../bad", doc, rev, &hash),
            Err(S3BindingError::InvalidInput)));
    }

    #[test]
    fn existing_scan_never_creates_database_or_follows_symlinks() {
        let dir = root();
        let path = dir.path().join("core").join(DB_NAME);
        assert!(S3BindingJournal::open_existing(dir.path()).unwrap().is_none());
        assert!(!path.exists());
        let db = S3BindingJournal::open(dir.path()).unwrap();
        drop(db);
        assert!(S3BindingJournal::open_existing(dir.path()).unwrap().is_some());
        fs::remove_file(&path).unwrap();
        #[cfg(unix)] {
            std::os::unix::fs::symlink(dir.path().join("outside"), &path).unwrap();
            assert!(matches!(S3BindingJournal::open_existing(dir.path()),
                Err(S3BindingError::UnsafeDatabase)));
        }
    }

    #[test]
    fn broken_schema_and_same_content_do_not_make_a_binding() {
        let dir = root();
        let (scope, doc, rev, hash) = fixture();
        let mut db = S3BindingJournal::open(dir.path()).unwrap();
        let p = db.prepare(&scope, doc, rev, &hash).unwrap();
        assert_eq!(p.phase, S3BindingPhase::Prepared);
        db.db.execute("UPDATE receive_bindings SET phase='bound' WHERE document_id=?1",
            params![doc.to_string()]).unwrap();
        // Simulate already-bound data written only by a FUTURE authenticated
        // File Gate implementation; this API cannot create that state.
        assert_eq!(db.status(&scope, doc).unwrap(), S3BindingLookup::Bound);
        assert!(matches!(db.prepare(&scope, doc, rev, &hash),
            Err(S3BindingError::Conflict)));
        db.db.execute("UPDATE receive_bindings SET source_scope='not-a-scope' WHERE document_id=?1",
            params![doc.to_string()]).unwrap_err(); // length constraint
    }

    #[test]
    fn independent_process_handles_reuse_one_uuid() {
        let dir = root();
        let (scope, doc, rev, hash) = fixture();
        let mut one = S3BindingJournal::open(dir.path()).unwrap();
        let mut two = S3BindingJournal::open(dir.path()).unwrap();
        let first = one.prepare(&scope, doc, rev, &hash).unwrap();
        let second = two.prepare(&scope, doc, rev, &hash).unwrap();
        assert_eq!(first.local_note_id, second.local_note_id);
    }
}
