# Android S4d7 - disposable Yandex Object Storage E2E fixture publisher

The Android reader accepts ONLY the fixed signed endpoint
https://storage.yandexcloud.net (region ru-central1). MinIO/custom endpoints
are not supported by the mobile signer and must not be used to pass the
installed Android integration test. This does not enable automatic sync.

## Scope and credentials

- Use a dedicated, empty akula-e2e-* test bucket with least-privilege
  object-scoped S3 credentials. Never point this utility at production data.
- Only the akula-e2e/ prefix is permitted. Each run creates a new Core
  VaultId, so a failed or interrupted test never replaces an old fixture.
- Use environment variables AKULA_YANDEX_TEST_BUCKET,
  AKULA_YANDEX_TEST_ACCESS_KEY_ID, AKULA_YANDEX_TEST_SECRET_ACCESS_KEY,
  AKULA_YANDEX_INVITE_PASSWORD (20+ bytes), and
  AKULA_YANDEX_FIXTURE_OUTPUT (fresh, absolute path). Optional
  AKULA_YANDEX_TEST_PREFIX defaults to akula-e2e/manual.
- DO NOT commit or upload the fixture directory, real .akula-sync encrypted
  invitation, password or cloud secrets as build artifacts. The invitation
  itself contains S3 credentials protected by a password and must be shared
  privately only with the test Android device.
- Do not print environment variables, aws --debug, upload response
  headers or AWS CLI stderr into public GitHub Actions logs.

## Generate Core V1 fixture (local, no network)

    cargo run --manifest-path rust/Cargo.toml -p mobakula-mobile-api --example yandex_s3_fixture
    python3 scripts/s3/publish_yandex_fixture.py "$AKULA_YANDEX_FIXTURE_OUTPUT"

The Core example writes exactly five V1 objects under objects/<S3 key>:
immutable Markdown BLAKE3 blob, immutable revision JSON, manifest shard,
manifest, and canonical refs/current.json head. It also writes
invite.akula-sync (password-encrypted, from the SAME Core contract)
and index.json containing only public typed IDs, byte lengths and SHA256
digests. Android receives the encrypted invitation and password separately.
CI runs this exact local generator with FAKE test credentials.

## Explicit conditional publication (separate approval)

With AWS CLI v2 installed and test variables set:

    python3 scripts/s3/publish_yandex_fixture.py "$AKULA_YANDEX_FIXTURE_OUTPUT" --publish

This is a real network operation against ONLY the fixed Yandex HTTPS S3 host,
never a MinIO mock. Before any write the script verifies bucket/prefix,
canonical IDs, object order, SHA256 and sizes. It then uses SigV4 AWS CLI
put-object with If-None-Match:* for each object, and obtains each object
back with get-object bound to its ETag, comparing ALL bytes locally.
The mutable root is published LAST and only if missing. Any 403, 404,
412, changed bytes or unexpected ETag stops the run; no blind retry,
overwrite, delete, bucket creation or cleanup is done.

The conditional upload behavior is documented by Yandex:
https://yandex.cloud/en/docs/storage/operations/objects/upload

## Installed Android acceptance

1. Install the latest debug APK ONLY on a disposable test profile/device.
   Open the app S3 import UI, add the generated invite.akula-sync
   and separate password. The test prefix must match index.json.
2. Check S3 from Android: exactly one document and one valid Markdown
   current candidate should be found after full genesis/index/revisions/blob
   verification. First observation is still untrusted.
3. Explicitly pin the S3 history, run check again, select the exact
   DocumentId, and approve one-time manual import. Note appears in the
   NORMAL note list and opens in the regular editor. Confirm body BLAKE3.
4. Force-stop and reopen Android Akula; verify notes and HistoryService
   remain durable and the journal is BOUND.
5. Attempt the same DocumentId again: must not create another note or
   overwrite local edits. Test a changed root/403/404/412 with a separate
   disposable vault; do not mutate a live bucket.
6. Record installed Android build commit, test bucket/prefix (without
   credential details), object hashes, CI/device evidence and FAIL/HOLD
   outcomes. No successful network test is claimed in this PR.

## Status boundary

A green fixture-export CI confirms executable Core wire-format generation
and offline-only publication checks. A real --publish invocation is a
separate user-authorized action, and Android installed S3 import is separate
again. Do not label the reader S3 synchronized after any of these steps:
this workflow is one-time, additive, user-approved import only.
