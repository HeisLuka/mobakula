//! App-private notes routed through Akula Core File Gate and HistoryService.
use aquilum_core::files::{document, error::FileCommandError, gate};
use aquilum_core::history::Source;
use aquilum_core::{Core, CoreEvent};
use serde::Serialize;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};
use uuid::Uuid;

const MAX_NOTE_BYTES: usize = 1024 * 1024;
const MAX_NOTE_COUNT: usize = 10_000;

#[derive(Clone, Debug, Serialize)]
pub struct Note {
    pub id: String,
    pub content: String,
    pub hash: String,
}

#[derive(Clone, Debug, Serialize)]
pub struct NoteSummary {
    pub id: String,
    pub title: String,
    pub modified_ms: u64,
}

#[derive(Debug)]
pub struct NotesError {
    pub code: &'static str,
    pub message: String,
}
impl NotesError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self { code, message: message.into() }
    }
    fn io(error: impl std::fmt::Display) -> Self {
        Self::new("io", error.to_string())
    }
}

fn gate_error(error: FileCommandError) -> NotesError {
    match error {
        FileCommandError::Conflict { .. } | FileCommandError::AlreadyExists { .. } =>
            NotesError::new("conflict", "Note changed on disk; reload before editing"),
        FileCommandError::NeedsReview { message } =>
            NotesError::new("uncertain", format!("Save may have committed: {message}; reload")),
        error => NotesError::io(error),
    }
}

/// The only writers used by this adapter are Akula File Gate operations.
pub struct NoteVault {
    pub base: PathBuf,
    notes: PathBuf,
    core: Arc<Core>,
    history_failures: Arc<Mutex<Vec<String>>>,
}

impl NoteVault {
    pub fn open(base: &Path) -> Result<Self, NotesError> {
        if !base.is_absolute() {
            return Err(NotesError::new("invalid_root", "App data path must be absolute"));
        }
        fs::create_dir_all(base).map_err(NotesError::io)?;
        let base = fs::canonicalize(base).map_err(NotesError::io)?;
        let notes = base.join("notes");
        let profile = base.join("core");
        fs::create_dir_all(&notes).map_err(NotesError::io)?;
        fs::create_dir_all(&profile).map_err(NotesError::io)?;
        if fs::symlink_metadata(&notes).map_err(NotesError::io)?.file_type().is_symlink() {
            return Err(NotesError::new("invalid_root", "Symlinked note directory refused"));
        }
        let history_failures = Arc::new(Mutex::new(Vec::new()));
        let failures = Arc::clone(&history_failures);
        let sink = Arc::new(move |event: CoreEvent| {
            if let CoreEvent::NoteHistorySaveFailed(failure) = event {
                if let Ok(mut pending) = failures.lock() {
                    pending.push(failure.message);
                }
            }
        });
        let core = Core::open(&profile, sink);
        let now_ms = SystemTime::now().duration_since(UNIX_EPOCH)
            .map_or(0, |elapsed| elapsed.as_millis() as i64);
        core.ui_state.resolve_workspace(&notes.to_string_lossy(), now_ms)
            .map_err(NotesError::io)?;
        Ok(Self { base, notes, core, history_failures })
    }

    fn path(&self, id: &str) -> Result<PathBuf, NotesError> {
        let parsed = Uuid::parse_str(id)
            .map_err(|_| NotesError::new("invalid_id", "Invalid note identifier"))?;
        Ok(self.notes.join(format!("{parsed}.md")))
    }

    fn check_note(&self, id: &str) -> Result<PathBuf, NotesError> {
        let path = self.path(id)?;
        let meta = fs::symlink_metadata(&path).map_err(NotesError::io)?;
        if !meta.is_file() || meta.file_type().is_symlink() {
            return Err(NotesError::new("invalid_file", "Only regular notes are allowed"));
        }
        if meta.len() > MAX_NOTE_BYTES as u64 {
            return Err(NotesError::new("too_large", "Note exceeds 1 MiB limit"));
        }
        Ok(path)
    }

    fn check_content(text: &str) -> Result<(), NotesError> {
        if text.len() > MAX_NOTE_BYTES {
            return Err(NotesError::new("too_large", "Note exceeds 1 MiB limit"));
        }
        Ok(())
    }

    fn begin_write(&self) -> Result<(), NotesError> {
        self.history_failures.lock().map_err(NotesError::io)?.clear();
        Ok(())
    }

    fn verify_history(&self, path: &Path) -> Result<(), NotesError> {
        if let Some(message) = self.history_failures.lock()
            .map_err(NotesError::io)?.last().cloned()
        {
            return Err(NotesError::new("uncertain", format!(
                "Primary note may be saved, but history failed: {message}; reload"
            )));
        }
        let history = self.core.history.page(&|| self.core.known_roots(), path, 0, 1);
        if history.versions.first().is_none_or(|version| !version.is_current) {
            return Err(NotesError::new(
                "uncertain", "History verification failed after save; reload before retry",
            ));
        }
        Ok(())
    }

    pub fn create(&self, content: &str) -> Result<Note, NotesError> {
        Self::check_content(content)?;
        self.begin_write()?;
        let id = Uuid::new_v4().to_string();
        let path = self.path(&id)?;
        gate::create(&self.core, &path, content, Source::Me, None)
            .map_err(gate_error)?;
        self.verify_history(&path)?;
        self.read(&id)
    }

    pub fn read(&self, id: &str) -> Result<Note, NotesError> {
        let path = self.check_note(id)?;
        let snapshot = document::read_file_snapshot_impl(&path).map_err(gate_error)?;
        Ok(Note { id: id.to_string(), content: snapshot.content, hash: snapshot.hash })
    }

    pub fn save(&self, id: &str, expected_hash: &str, content: &str)
        -> Result<Note, NotesError>
    {
        Self::check_content(content)?;
        if expected_hash.len() != 64 || !expected_hash.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(NotesError::new("invalid_hash", "Expected BLAKE3 hash is required"));
        }
        let path = self.check_note(id)?;
        self.begin_write()?;
        gate::write(&self.core, &path, content, Some(expected_hash), Source::Me, None)
            .map_err(gate_error)?;
        self.verify_history(&path)?;
        self.read(id)
    }

    pub fn list(&self) -> Result<Vec<NoteSummary>, NotesError> {
        let mut notes = Vec::new();
        for entry in fs::read_dir(&self.notes).map_err(NotesError::io)? {
            let entry = entry.map_err(NotesError::io)?;
            let name = entry.file_name();
            let name = name.to_string_lossy();
            let Some(id) = name.strip_suffix(".md") else { continue };
            let Ok(uuid) = Uuid::parse_str(id) else { continue };
            if notes.len() >= MAX_NOTE_COUNT {
                return Err(NotesError::new("too_many", "More than 10,000 notes"));
            }
            let note = self.read(&uuid.to_string())?;
            let title = note.content.lines().find(|line| !line.trim().is_empty())
                .map(|line| line.trim().trim_start_matches("# ").chars().take(100).collect())
                .unwrap_or_else(|| "Без названия".to_string());
            let modified_ms = fs::metadata(entry.path()).map_err(NotesError::io)?
                .modified().ok()
                .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
                .map_or(0, |age| age.as_millis() as u64);
            notes.push(NoteSummary { id: note.id, title, modified_ms });
        }
        notes.sort_by(|a, b| b.modified_ms.cmp(&a.modified_ms).then(a.id.cmp(&b.id)));
        Ok(notes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn note_round_trip_survives_core_reopen_and_rejects_stale_hash() {
        let dir = tempfile::tempdir().unwrap();
        let vault = NoteVault::open(dir.path()).unwrap();
        let created = vault.create("# Первая\nТекст 🦈\n").unwrap();
        let saved = vault.save(&created.id, &created.hash, "# Изменено\nТекст 🦈\n").unwrap();
        assert_eq!(vault.read(&saved.id).unwrap().content, saved.content);
        assert!(vault.list().unwrap().iter().any(|n| n.id == saved.id));
        assert_eq!(vault.save(&saved.id, &created.hash, "stale").unwrap_err().code, "conflict");
        drop(vault);
        let reopened = NoteVault::open(dir.path()).unwrap();
        assert_eq!(reopened.read(&saved.id).unwrap().content, "# Изменено\nТекст 🦈\n");
    }

    #[test]
    fn invalid_ids_and_oversized_writes_do_not_escape_vault() {
        let dir = tempfile::tempdir().unwrap();
        let vault = NoteVault::open(dir.path()).unwrap();
        assert_eq!(vault.read("../outside").unwrap_err().code, "invalid_id");
        let note = vault.create("ok").unwrap();
        assert_eq!(vault.save(&note.id, &note.hash, &"x".repeat(MAX_NOTE_BYTES + 1))
            .unwrap_err().code, "too_large");
        assert_eq!(vault.read(&note.id).unwrap().content, "ok");
    }
}
