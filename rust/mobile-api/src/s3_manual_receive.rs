//! S4d4a: one explicitly approved, integrity-verified external Markdown import.
//! The network origin is still untrusted: this is manual import, NOT S3 sync.
//! The JNI boundary must first verify a pinned S3 checkpoint, exact root,
//! revision DAG and all blobs in one live session. No path from S3 is used.

use crate::notes::{NoteVault, NotesError};
use crate::s3_binding_journal::{
    S3BindingError, S3BindingJournal, S3BindingLookup,
};
use crate::s3_receive_plan::S3ProvisionalCandidate;
use uuid::Uuid;

const MAX_MARKDOWN_BYTES: usize = 1024 * 1024;

#[derive(Debug)]
pub enum S3ManualReceiveError {
    NoConsent,
    InvalidContent,
    AlreadyPreparedOrBound,
    Journal(S3BindingError),
    /// A file may have been created before an error was reported. Never
    /// repeat creation automatically; the local UUID is kept for review.
    NeedsReview { local_note_id: Uuid },
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct S3ManualReceiveReceipt {
    pub local_note_id: Uuid,
    pub document_id: aquilum_core::sync::DocumentId,
    pub revision_id: aquilum_core::sync::RevisionId,
    pub source_scope: String,
    pub content_blake3: String,
    pub imported: bool,
    pub synchronized: bool,
    pub remote_owner_verified: bool,
}

fn validate_source_bytes<'a>(
    candidate: &S3ProvisionalCandidate, raw: &'a [u8],
) -> Result<&'a str, S3ManualReceiveError> {
    if raw.len() != candidate.bytes || raw.len() > MAX_MARKDOWN_BYTES
        || raw.contains(&0) || raw.contains(&b'\r')
        || blake3::hash(raw).to_hex().as_str() != candidate.blob_blake3
    {
        return Err(S3ManualReceiveError::InvalidContent);
    }
    std::str::from_utf8(raw).map_err(|_| S3ManualReceiveError::InvalidContent)
}

/// The only entrypoint to the first additive, single-document S3 receive.
/// Never permits updates/deletions. Same hash does not allow re-import.
pub fn manually_import_one_checked_document(
    vault: &NoteVault, scope: &str,
    candidate: &S3ProvisionalCandidate, raw: &[u8], user_confirmed: bool,
) -> Result<S3ManualReceiveReceipt, S3ManualReceiveError> {
    if !user_confirmed { return Err(S3ManualReceiveError::NoConsent); }
    let text = validate_source_bytes(candidate, raw)?;
    let mut journal = S3BindingJournal::open(&vault.base)
        .map_err(S3ManualReceiveError::Journal)?;
    if journal.lookup(scope, candidate.document_id)
        .map_err(S3ManualReceiveError::Journal)?.is_some()
    {
        return Err(S3ManualReceiveError::AlreadyPreparedOrBound);
    }
    // This commits the durable identity BEFORE File Gate publication.
    // If power is lost, that UUID stays PREPARED and is never retried blindly.
    let intent = journal.prepare_fresh(
        scope, candidate.document_id, candidate.revision_id,
        &candidate.blob_blake3,
    ).map_err(S3ManualReceiveError::Journal)?;
    let result: Result<(), NotesError> = (|| {
        let created = vault.create_reserved(intent.local_note_id, text)?;
        // create_reserved has verified File Gate create, current HistoryService
        // and fresh disk readback. Check immutable source hash again.
        if created.hash != candidate.blob_blake3
            || created.content.as_bytes() != raw
            || created.id != intent.local_note_id.to_string()
        {
            return Err(NotesError {
                code: "uncertain", message: "Imported note readback mismatch".into(),
                note_id: Some(intent.local_note_id.to_string()),
            });
        }
        Ok(())
    })();
    if result.is_err() {
        // In case of DB failure, PREPARED also blocks re-import.
        let _ = journal.hold(scope, candidate.document_id);
        return Err(S3ManualReceiveError::NeedsReview {
            local_note_id: intent.local_note_id,
        });
    }
    if journal.confirm_with_receipt(
        &intent, intent.local_note_id, &candidate.blob_blake3,
    ).is_err() {
        let _ = journal.hold(scope, candidate.document_id);
        return Err(S3ManualReceiveError::NeedsReview {
            local_note_id: intent.local_note_id,
        });
    }
    Ok(S3ManualReceiveReceipt {
        local_note_id: intent.local_note_id, document_id: candidate.document_id,
        revision_id: candidate.revision_id, source_scope: scope.to_owned(),
        content_blake3: candidate.blob_blake3.to_owned(), imported: true,
        synchronized: false, remote_owner_verified: false,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use aquilum_core::sync::{DocumentId, RevisionId};

    fn fixture(body: &[u8]) -> S3ProvisionalCandidate {
        S3ProvisionalCandidate {
            document_id: DocumentId::new(),
            revision_id: RevisionId::new(),
            blob_blake3: blake3::hash(body).to_hex().to_string(),
            bytes: body.len(),
        }
    }
    fn local() -> (tempfile::TempDir, NoteVault) {
        let dir = tempfile::tempdir().unwrap();
        let vault = NoteVault::open(dir.path()).unwrap();
        (dir, vault)
    }
    #[test]
    fn user_approved_markdown_is_new_local_note_and_history_survives_restart() {
        let (dir, vault) = local();
        let scope = "a".repeat(64);
        let data = b"# Remote note\nPlain text\n";
        let candidate = fixture(data);
        assert!(matches!(manually_import_one_checked_document(
            &vault, &scope, &candidate, data, false),
            Err(S3ManualReceiveError::NoConsent)));
        assert!(S3BindingJournal::open_existing(&vault.base).unwrap().is_none());
        assert!(vault.list().unwrap().is_empty());
        let receipt = manually_import_one_checked_document(
            &vault, &scope, &candidate, data, true,
        ).unwrap();
        assert!(receipt.imported && !receipt.synchronized);
        assert!(!receipt.remote_owner_verified);
        assert_ne!(receipt.local_note_id.to_string(), candidate.document_id.to_string());
        assert_eq!(vault.read(&receipt.local_note_id.to_string()).unwrap().content,
            String::from_utf8(data.to_vec()).unwrap());
        assert_eq!(vault.list().unwrap().len(), 1);
        assert!(matches!(manually_import_one_checked_document(
            &vault, &scope, &candidate, data, true),
            Err(S3ManualReceiveError::AlreadyPreparedOrBound)));
        drop(vault);
        let reopened = NoteVault::open(dir.path()).unwrap();
        let journal = S3BindingJournal::open_existing(&reopened.base).unwrap().unwrap();
        assert_eq!(journal.status(&scope, candidate.document_id).unwrap(),
            S3BindingLookup::Bound);
        assert_eq!(reopened.read(&receipt.local_note_id.to_string()).unwrap().hash,
            receipt.content_blake3);
    }
    #[test]
    fn forged_mismatched_binary_crlf_and_nul_never_write_notes() {
        let (_dir, vault) = local();
        let scope = "a".repeat(64);
        for input in [b"corrupt".as_slice(), b"# CRLF\r\n", b"\xff", b"ok\0bad"] {
            let claimed = fixture(b"legitimate");
            assert!(matches!(manually_import_one_checked_document(
                &vault, &scope, &claimed, input, true),
                Err(S3ManualReceiveError::InvalidContent)));
        }
        assert_eq!(vault.list().unwrap().len(), 0);
    }
    #[test]
    fn interrupted_journal_or_collision_never_retries_or_overwrites() {
        let (_dir, vault) = local();
        let scope = "a".repeat(64);
        let body = b"remote";
        let candidate = fixture(body);
        let intent = {
            let mut journal = S3BindingJournal::open(&vault.base).unwrap();
            journal.prepare_fresh(&scope, candidate.document_id,
                candidate.revision_id, &candidate.blob_blake3).unwrap()
        };
        // Simulate another local process saving a note under that reserved ID.
        vault.create_reserved(intent.local_note_id, "unrelated local bytes").unwrap();
        assert!(matches!(manually_import_one_checked_document(
            &vault, &scope, &candidate, body, true),
            Err(S3ManualReceiveError::AlreadyPreparedOrBound)));
        assert_eq!(vault.read(&intent.local_note_id.to_string()).unwrap().content,
            "unrelated local bytes");
        assert_eq!(vault.list().unwrap().len(), 1);
    }
    #[test]
    fn empty_markdown_import_is_supported_and_never_synced() {
        let (_dir, vault) = local();
        let candidate = fixture(b"");
        let receipt = manually_import_one_checked_document(&vault, &"a".repeat(64),
            &candidate, b"", true).unwrap();
        assert!(receipt.imported && !receipt.synchronized);
        assert_eq!(vault.read(&receipt.local_note_id.to_string()).unwrap().content, "");
    }
}
