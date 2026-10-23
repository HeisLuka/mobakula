# Android S4d1 - advisory local UUID slot inspection (no writes)

Depends on #38 current-head receive plan, #37 immutable content proof,
#36 revision metadata, #34 verified index and pinned invitation ancestry.

## Read-only operation

- After the exact-root BLAKE3 proof and current document-head planning,
  a new native JNI entrypoint examines local app-private note slots.
- Each typed remote DocumentId is converted to a canonical UUID basename
  in the existing NoteVault directory; an untrusted remote path is NEVER
  used to open a local file.
- NoteVault checks the local notes root, symlinks, file kind, 1 MiB bound
  and the existing Core document snapshot/read operation.
- The result has four strictly advisory categories:
  **absent**, **same content but unbound**, **different content**, **unsafe**.
- The JNI verifier requires the SAME native session and head as the completed
  blob content proof, not merely a successful manifest or metadata preview.
- Android only displays aggregate counts, not local paths or file bodies.
  When NoteVault is not initialized, the scan is explicitly unavailable,
  not reported as zero conflicts.

## No authorization

A matching remote UUID and BLAKE3 hash do NOT establish the origin of a
local note. The scan never sets localIdentityBound, importAuthorized,
remoteOwnerVerified or synced; all remain false. Absent is also NOT a
reservation: another local writer may create the file immediately after
the inspection. Any actual writer must independently recheck using File
Gate create-if-absent, never direct fs::write or a blind overwrite.

This PR never creates, overwrites, deletes, acknowledges or replays notes;
it never persists a remote identity mapping or baseline. It introduces no
network privileges and no S3 credential storage.

## Remaining blockers

1. Origin-bound publisher authority and durable anti-rollback checkpoint.
2. Durable map (S3 provider scope + vault + DocumentId) to owned local UUID,
   with crash-safe intent journal and no duplicate imports.
3. Explicit receive consent, atomic no-replace File Gate operation, verified
   history/readback and interrupted-process recovery.
4. Tests on actual Android with a desktop-generated nonempty S3 fixture.
   Android/JNI compile CI alone does not constitute runtime acceptance.

Tests include missing/same/different/symlinked slots, malformed hashes,
forged counters, and a not-yet-initialized NoteVault. Keep Draft.
