# Android S4d4 - manual single-Markdown S3 receive (NO automatic sync)

Stack: PR #41 durable source/DocumentId/local-UUID journal, #40
user-pinned checkpoint, #39 local-conflict readback, #38 current-head
Markdown selection, #37 immutable blob proof, and all preceding S3
read-only proofs. This stage never edits an existing local note.

## User-visible workflow

1. In Files / S3, configure an invited read-only S3 profile.
2. Press "Check S3" to verify full ancestry, index, revision metadata,
   content hashes and current root. It does NOT grant write authority.
3. First pin the verified S3 history with the existing explicit action.
   Run the check again so the **prior pinned** checkpoint matches.
4. Select exactly ONE displayed remote Markdown DocumentId, press
   "Import", then read and accept a separate explicit confirmation that
   the S3 publisher/account has NOT been independently authenticated.
5. Android starts a new 180s native session and repeats ALL checks.
   It requires the previously pinned checkpoint to be unchanged and
   exact-root proof to match the current selected document, revision,
   and blob. It refuses stale, foreign, forked or unsupported documents.
6. The same-session JNI entrypoint accepts only that allowlisted typed
   document, its exact BLAKE3 body and the rooted checkpoint. It NEVER
   takes a path or local filename from S3. The payload is zeroized.
7. Portable Rust writes a PREPARED journal intent with a new local
   random UUID under (provider namespace fingerprint, DocumentId).
   The fresh-only transaction rejects existing PREPARED/HOLD/BOUND rows
   before any publication, even if a hash matches after a restart.
8. NoteVault calls the existing Akula File Gate create_new(true)
   using the local UUID, followed by current HistoryService verification
   and disk readback. Only an identical exact body hash permits the
   journal to transition PREPARED -> BOUND. The Android note list is
   refreshed, and the new note can be opened in the standard editor.

## Crash and conflict behavior

- If File Gate, history, readback or SQLite promotion is uncertain,
  return NEEDS REVIEW with the reserved local UUID. Record HOLD if
  possible. A recovered PREPARED row likewise cannot silently retry.
- Interrupted operations may have created a local note. NEVER erase,
  blindly overwrite, duplicate or auto-acknowledge it.
- The DB has a UNIQUE local UUID and unique (source, DocumentId).
  Other S3 scopes may not claim that same local note UUID.
- A repeated import of a BOUND document refuses creation: this is NOT
  a merge/update operation.
- A manual edit/deletion of a mapped local file makes its scan result
  HOLD instead of an "exact" imported-content claim.
- Partial snapshot beyond 64 revisions or 16 blobs, changed S3 root,
  corrupted blob, missing pinned checkpoint, invalid UTF-8, CRLF/NUL
  or >1 MiB note body always refuses import.
- No S3 PUT, automatic inbound updates, tombstone application,
  one-click bulk import, local overwrite or background sync.

## Security / trust distinction

The manually selected source is explicitly treated as an UNTRUSTED
external Markdown file. BLAKE3 is an integrity check relative to that
source's own immutable references, NOT a cryptographic publisher
identity proof. User confirmation grants a one-time permission to
create one locally isolated note; it does not grant a reusable
publisher/synchronization identity or any remote filesystem authority.
The checkpoint is device-local TOFU and not hardware monotonic storage.

## Acceptance

Rust tests: creation + HistoryService across restart, no-consent,
same-document replay prevention, recovered PREPARED collision,
bad BLAKE3/binary/CRLF/NUL, zero-byte Markdown.
Kotlin JVM: candidate UUID/hash/count/bounds validation; Compose
dialog is explicitly gated by previously-pinned state.
Mobile gate: Rust, both JNI ABIs, Kotlin, Android APK, policy.
Still required before production: installed-device integration against
a desktop-produced nonempty MinIO/Yandex fixture, process-death between
journal intent and File Gate, interrupted HistoryService/readback,
link/symlink/collision tests, wrong-publisher controls, user review of
Markdown before rendering, release packaging and upgrade testing.
