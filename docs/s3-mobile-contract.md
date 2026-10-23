# Android S3 — S0 contract evidence (read-only foundation)

## Status

**S0 implementation in progress, S1–S7 NOT implemented.** This branch only checks the
structure of a proposed S3 head and immutable manifest using the *actual upstream
Akula Core types*. It does not contact S3, read an encrypted invitation, authenticate
a publisher, import a profile, or read/write a note. It cannot mark a device synced.

- Mobile base: `d786ee3fa78c227ba470868653edc19a5beef091` (local NoteVault, JNI, crash-draft recovery).
- Desktop Core review pin candidate: `58a15187b790ade76b5e70fed9342b789f3623fe` ([upstream draft PR #688](https://github.com/Lalalalendia/Akula/pull/688)); **not merged, pin is experimental**.
- Previous mobile Core pin: `d4487a78083150924a7a8b8ea85693d931041960`.
- Source of S3 keyspace/head: `aquilum-app/core/src/sync/s3.rs`.
- Source of manifest/digest: `aquilum-app/core/src/sync/s3_manifest.rs`.
- S3 transport remains behind heavy `sync-s3`; V1 invite crypto now uses
  portable `sync-s3-invite` from upstream PR #688, **without** AWS SDK or host custody.
  Android still has no S3 network transport or Keystore-backed credential store.

## Existing desktop wire contract, not a new one

- S3 object root: `<prefix>/v1/vaults/<cloud_vault_id>/`.
- Mutable pointer: `refs/current.json` (CAS-controlled by provider).
- Immutable manifest: `manifests/<manifest_id>.json`; revision and content objects
  are separate immutable keys. A listing is **not** an authoritative snapshot.
- Root pointer V1: `schema=1`, `vault_id`, `generation`, `manifest_id`,
  `manifest_blake3`, `previous_manifest_id`. Canonical exact bytes only in
  S0 preview; max pointer 4096 bytes.
- Immutable manifest V1: `schema=1`, `vault_id`, `manifest_id`, `generation`,
  `previous_manifest_id`, `previous_manifest_blake3`,
  `total_revisions`, `shards`; max 512 KiB.
- Typed UUID identities are shared Core types; BLAKE3 digest is *not* an S3 ETag.

## Invite V1 compatibility finding

Upstream `s3_pair_invite.rs` currently uses magic `AKS3V1\0\0`, fixed
600,000-iteration **PBKDF2-HMAC-SHA256**, 16-byte salt, 12-byte nonce, and
AES-256-GCM with the full header as AAD; max envelope size 16 KiB. The design
notes referring to Argon2id describe a *future incompatible version*, not
the active V1. Android must call the upstream authenticated decryptor, never
copy this encryption logic into Kotlin. An authenticated invite still needs a
separately approved remote genesis/publisher trust proof.

## Scope of this implementation

`mobile-api::s3_contract::inspect_untrusted_s3_shape` validates the pinned
Core keyspace, exact canonical head and manifest bytes, matching vault IDs,
generation, ancestry pointer, immutable manifest hash and bounded sizes. The
result is deliberately typed `UntrustedS3Shape` because self-consistent S3
bytes prove neither publisher authority nor real origin.

Tests use fixed UUIDs and the desktop Core's public V1 constructors to
verify exact wire field layout and reject wrong vault/prefix, unknown authority
fields, changed digest, generation mismatch, noncanonical, truncated and
oversized input. The selected upstream pin additionally must pass the *whole*
existing Android JNI + notes build. Updating the pin changes the underlying
note Core: do not merge without regression review and Android runtime checks.

## S0 exit gate, still open

1. Source pin builds and runs mobile unit + Android ABI matrix.
2. A **desktop-produced** immutable MinIO fixture (not merely a mobile-generated
   fixture) validates against the mobile Core with its pinned genesis, including
   negative tamper/foreign-vault samples.
3. Owner-approved publisher/genesis proof identified; structural preview must
   not become a File Gate write permit.
4. Document the S1 Rust/Android transport feature isolation and encrypted
   invite trust boundary before enabling `sync-s3`.

No KVM, S3 credentials, S3 writes, or permissions are introduced in this PR.

## Offline invitation inspection (S1 foundation, not S2 completed)

`mobile-api::s3_invite::inspect_untrusted_s3_invite` calls upstream Core's
existing V1 PBKDF2+AES-GCM authenticated decryptor. It returns a struct with
ONLY non-secret provider/bucket/prefix/cloud VaultId/genesis fingerprint; it
never exposes or serializes the access key or secret. The temporary
`S3PairInvite` drops and zeroizes its keys. This is not an S3 trust or
membership grant; the profile and fingerprint remain **untrusted** until
independently verified against remote pinned genesis and owner policy.

The routine currently has no Android UI/JNI entrypoint; passwords are never
sent through a JSON-native command or persisted into application storage.
Tests cover wrong password, corrupted ciphertext, header/KDF changes and
oversize. The unmerged desktop Core PR #688 must receive manual security
review and merge before replacing the experimental pin with a stable revision.

## Android offline preview (not a connection or pairing grant)

Under **Files** the `.akula-sync` checker reads at most 16 KiB through SAF,
accepts a masked non-saveable password, and passes an encrypted ByteArray plus
transient mutable CharArray to a dedicated JNI method. The ordinary JSON
`request` path carries no password; Rust zeroizes its transient UTF-16 and
UTF-8 passwords and only serializes non-secret fields and `trusted=false`.
Kotlin Compose temporarily holds an immutable String, so this is **not** an
at-rest credential store. The field is cleared when submitted; Android
Keystore import and verified genesis still require separate S2/S3 work.
No INTERNET permission or automatic S3 access is added.
