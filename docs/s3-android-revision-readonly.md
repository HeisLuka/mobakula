# Android S4b — immutable revision metadata over signed S3 reads

Dependency stack: #28 Keystore -> #31 ancestry -> #32 scoped SigV4 ->
#33 stable Android HEAD/GET -> #34 sharded index -> #35 offline
Core revision proof -> this read-only network integration.

## Actual behavior
- The same short-lived native session must prove the complete genesis chain
  for the current root, then the referenced immutable V1 index.
- Only after successful index validation does JNI install a per-session
  allowlist of exact typed revision UUIDs from that index.
- The Rust signer rejects every revision UUID outside that allowlist, any
  arbitrary key, PUT/DELETE/LIST and malformed ETag/date. No remote mutation.
- Android fetches each authorized revisions/<UUID>.json via
  **HEAD -> signed If-Match GET -> HEAD**, with fixed Yandex HTTPS host,
  redirects disabled, exact ETag and content-length stability, and 64 KiB per
  revision JSON. It then rereads the **entire current head and ETag**.
- JNI checks BLAKE3 of exact revision JSON, document/revision identity,
  content/rename/tombstone consistency, duplicate IDs and Core RevisionDag
  ancestry, including rejecting missing parents.
- UI reports revision metadata count but always says owner not independently
  verified and sync not enabled. It never displays local files as synced.

## Bounds and fallback
- Maximum 64 revision records / 4 MiB total in this first proof.
- If the already-verified index exceeds 64 records, the existing S3d
  **index-only preview still succeeds**, but the UI explicitly reports that
  revision metadata was **not** checked. Never reinterpret a cap as an empty
  index, and never silently import a partial DAG.
- Existing 16-shard, 128-ancestor, 8 MiB ancestry, 640-signature and
  180-second expiring-session constraints remain in force.
- On failed read/hash/DAG/race check, the entire metadata proof fails closed.
  Native signer credentials expire or are dropped at session end.

## Not implemented or accepted
No remote owner/account identity proof, rollback-resistant checkpoint, body
blob read, local NoteVault/File Gate import, S3 upload, or durable sync
baseline. Full S4c/S4d and S5 are distinct later work. No S3 credentials are
placed in CI, the notes JSON path or regular app settings.

Acceptance still requires mobile Rust, both JNI ABIs, Kotlin/APK, policy CI;
independent desktop-generated nonempty MinIO data and a real Android/Yandex
read-only test including 403/404/412, tampering, rollback, changed root,
partial revision, timeouts and cancellation. Emulator runtime depends on a
working KVM-capable runner; a green compilation does not replace device E2E.
