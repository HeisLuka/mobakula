# Android S4d2: device-local S3 head continuity checkpoint

Dependencies: #39 local-slot preflight, #38 receive candidate classification,
#37 immutable blob proof, #36 revision HTTPS, #35 metadata, #34 sharded
index, #31 genesis, #28 protected invitation.

## What actually changes

1. Portable Rust derives an origin scope fingerprint from provider, endpoint,
   region, bucket, prefix, VaultId and pinned genesis. Access key rotation
   does not change the scope; provider/bucket/vault changes invalidate it.
   No access keys or passwords enter the scope hash.
2. A verified current S3 HEAD is represented by a canonical V1 local
   checkpoint: scope fingerprint, generation, manifest UUID and immutable
   manifest BLAKE3. This is never S3's mutable ETag.
3. Each Android probe loads an optional Android-Keystore AES-GCM checkpoint
   from noBackupFilesDir. It independently verifies full current ancestry
   in Rust and requires the previous manifest ID, generation and digest to
   exist in that exact validated chain. Lower generation = rollback,
   same-generation mismatched identity = fork, higher generation without
   the pinned predecessor = divergence. A changed scope is also rejected.
4. JNI refuses index/revision/blob access until the local checkpoint
   comparison succeeds for the SAME root in the same short-lived session.
   Any repeated or failed comparison revokes prior index/blob allowances.
5. The read-only probe never updates persistent checkpoint state by itself.
   Only after all bounded content blobs pass BLAKE3 and root recheck may
   the UI offer an explicit 'Pin checked S3 state' action. The promotion
   performs an atomic previous-byte compare-and-set and an AES-GCM write.
   Unrelated modifications to notes, File Gate or sync baselines remain
   prohibited.
6. Missing checkpoint data while an Android Keystore anchor key exists,
   missing key for an existing sealed file, malformed ciphertext and
   corrupted plaintext all fail closed. A newly paired device with no
   checkpoint is explicitly marked first observation, not trusted/synced.

## Security boundary and limitations

- This is a TOFU/continuity defense against a remote service returning a
  head older or divergent from a *previously user-pinned* observation.
- It does NOT establish the identity or permission of the remote publisher.
  A malicious server could serve a self-consistent but malicious future
  history. User-pinned genesis likewise does not prove ownership.
- Android Keystore sealing does not provide a hardware monotonic counter.
  A privileged adversary who can roll back both local app/Keystore state,
  or replay previously valid ciphertext while the same key remains, is
  outside this protection. Export and restore require separate protocol.
- A new device without a local pin cannot detect remote history rollback.
  First observation must not authorize an import. No file creation or update,
  no remote writes, no durable DocumentId->local note map, no receipt.
- If the 128-ancestor / 16-shard / 64-revision / 16-blob preview caps are
  exceeded, import remains unavailable. No checkpoint promotion is offered
  unless FULL bounded content verification passed.

## Validation and acceptance

Portable Rust tests: first observation, unchanged, real successor,
rollback, same-generation fork, divergent ancestry, foreign origin scope,
malformed/noncanonical checkpoint, broken genesis. SigV4 scope test:
credential rotation keeps the anchor namespace stable, bucket change does
not.

CI: exact-commit Rust tests, policy, JNI ARM64+x86_64, Kotlin tests and APK.
Runtime still required on installed Android and desktop-generated S3 fixture,
with cancel/restart, tampered Keystore ciphertext, missing checkpoint file,
network 403/404/412 and S3 HEAD changing mid-transfer. CI success alone
is not device acceptance.

Next work is an owner-authority model, durable scoped note mapping, explicit
user consent and atomic File Gate create-without-replace, followed by
HistoryService readback, no-blind-retry recovery and real-device E2E.
