# Android S4d3 - source-scoped durable receive-intent journal

Dependency stack: #40 checkpoint, #39 local slot preflight, #38 remote
candidate classification, #37 complete BLAKE3 blob proof, #36 metadata
transport, #35 revision verification, #34 sharded index, and genesis.

## Storage contract

Portable Rust uses a separate app-private SQLite journal under the existing
NoteVault Core directory. SQLite uses WAL, synchronous FULL, IMMEDIATE
transactions, a bounded 3-second busy timeout, CHECK constraints and a
10,000-row limit. The source and blob hashes must be lowercase 64-hex. DB
symlinks/nonregular objects, unknown schema and malformed rows fail closed.

The composite remote identity is:
- S3 source scope fingerprint: provider, endpoint, region, bucket, prefix,
  cloud VaultId and genesis (not credentials)
- Typed immutable remote DocumentId

The journal reserves a DISTINCT, locally generated random UUID per remote
identity. The remote DocumentId is NEVER used as a local filename after this
stage. The local UUID cannot be assigned to two sources/documents (UNIQUE).

The schema retains revision ID, immutable blob hash and phase:
- prepared: locally journaled intent, NOT a file or path reservation
- bound: future state for File Gate + HistoryService verified imports only
- hold: uncertainty, collision or interrupted operation; never auto-retry

This stage intentionally exposes NO runtime mark_bound API. A matching
remote UUID or content hash is not proof of a previously imported note.
A repeated exact prepared intent returns the SAME locally reserved UUID
even after restart. Changing revision/hash/scope, resubmitting HOLD/BOUND,
or attempting to hijack an existing identity fails closed. Recovery from
PREPARED + any possibly published local file requires future explicit
reconciliation; it may NOT automatically create another note or upgrade
to BOUND merely because the contents match.

## Android functionality

The native read-only S3 probe reads the existing registry ONLY after the
genesis, local checkpoint, complete revision graph and blob BLAKE3 proofs
have all passed in the SAME short-lived session. It never initializes a
database on first read and never receives an arbitrary path from S3.

The UI shows:
- exact bound identities (future)
- bound-but-older remote revisions (future conflict, NOT automatically updated)
- pending prepared intents
- held/contradictory intents
- unmapped remote documents

If NoteVault has not been initialized, it shows the scan as unavailable,
not as an empty verified mapping. Rust and Kotlin both return
remoteOwnerVerified=false, importAuthorized=false, synced=false.

## Explicit non-goals

No local note files created, updated, deleted or associated yet; no
authorization to run File Gate, no remote S3 PUT/DELETE, no implicit
publisher trust, no durable sync baseline or read receipt. This is a
persistent identity and intent substrate for a later explicit S4d4
receive action. Source-scoped journal state is app-private metadata, not
hardware rollback-resistant or cryptographically sealed by itself.

## Acceptance

- Rust SQLite reopen/retry/race conflict, scope partition, HOLD persistence,
  schema/symlink invalidity, no-silent-Bound tests.
- Kotlin count/status verification, including absent registry, forged
  success flags, partial/unavailable scan and negative counts.
- Existing CI: policy, portable Rust, Android JNI ARM64 + x86_64, Kotlin unit
  tests, APK; real device restore and import are NOT yet demonstrated.
- S4d4 must integrate atomic File Gate create-if-absent with a trusted
  publisher decision, explicit user approval, durable receive journal,
  HistoryService readback, no-blind-retry and device E2E.
