# S4d8 - live Yandex Object Storage to installed Android acceptance

This stage is an **opt-in test harness**, not a claim that any real Yandex
account has been exercised. It does not add automatic S3 sync or overwrite
permissions to Akula. Normal Mobile gate builds the new instrumentation test
but never executes it against cloud storage.

## Preconditions

1. Use the **test-only fixture publisher** in PR #46. Generate one fresh
   Core V1 Markdown fixture and publish its five objects to an empty
   akula-e2e-* Yandex Object Storage test bucket (akula-e2e/ prefix).
   The encrypted invite must contain ONLY the separate scoped GET/HEAD
   identity. Keep publication credentials completely outside Android.
2. Ensure the object graph is actually published and byte-for-byte read back.
   A green offline CI or fixture generator alone does not satisfy this.
3. Use a disposable, debuggable Android emulator with matching app APK
   and androidTest APK installed. Never run against a regular user phone,
   nondebug build, personal note vault or production S3 bucket.
4. Set AKULA_YANDEX_INVITE_PASSWORD locally (same separate passphrase
   used when generating invite.akula-sync). Do not pass it on the command
   line, in adb instrumentation arguments, as a GitHub build artifact,
   or in a PR/comment. The password and test read-only S3 credentials
   are sensitive even though this is a disposable test account.

## Invoke only after the fixture is published

    python3 scripts/android/yandex_live_receive_probe.py \
      "$AKULA_YANDEX_FIXTURE_OUTPUT" \
      --ack-disposable-emulator --ack-test-import

Optional --serial specifies a particular emulator. The host runner:

- Refuses to run without BOTH consent flags, a Core V1 public fixture
  inventory, a passphrase in the local environment, both installed
  matching debug APKs, and an Android ro.kernel.qemu=1 device.
- Uses adb run-as to stage the encrypted invite and password by stdin
  in app-private no_backup/s3-live-fixture; no secrets are ever passed
  as ADB command-line arguments, remote URLs, or test arguments.
- Stages public index.json as the exact expected document/revision/blob.
  The instrumented test creates an isolated test vault inside app-private
  noBackupFilesDir. It never reads or modifies the user's note vault.
- Starts genuine HTTPS HEAD/GET against the FIXED Yandex endpoint using
  the normal signed S3 Android code. Only one nonempty Markdown current
  document is accepted. It reads full V1 genesis/index/revisions/blobs
  with the same mandatory ETags, SHA256 signature and BLAKE3 validation
  as the normal Android app.
- Obtains a first TOFU history observation, explicitly pins it into
  Android Keystore, rechecks the current head and then invokes exactly
  one additive File Gate import using the verified candidate revision.
  Checks the new note content hash, regular note listing, local UUID
  and unsynced, unauthenticated-source receipt.
- Performs a real app force-stop between two independent instrumentation
  invocations. From a fresh process it reopens the saved local note,
  checks checkpoint/journal BOUND, attempts importing the SAME source
  DocumentId again, and requires an exact typed existing-binding refusal.
  An HTTP timeout, credential failure or malformed response MUST fail the
  test instead of masquerading as duplicate protection. The note count
  stays exactly one. Once verification succeeds, the test deletes its
  Keystore-protected S3 invitation and key while retaining only the
  disposable note and journal for audit.
- Deletes staging files containing the invitation, password and public
  index even on test failure, without deleting imported test notes
  needed for follow-up inspection. Keystore/SQLite remain isolated
  under a new scope derived from the fixture VaultId.

## Evidence required

Two independent Android instrumentation receipts:
- akula_s3_live=imported_once
- akula_s3_live=reopened_no_duplicate

The runner requires exactly OK (1 test) for each receipt, not mere
instrumentation exit code 0. It prints no cloud key material.

Record the test APK commit, non-sensitive bucket/prefix, V1 hashes,
whether the actual network test passed, and why failures occurred.
Do not publish screenshots showing access keys or invite passwords.

## Explicit limits

A passing run would prove ONE test Markdown received from Yandex via
the Android application, HistoryService durability, and duplicate
refusal. It would NOT prove cloud-owner identity, bulk import, full
two-way sync, S3 PUSH/PUT from Android, conflict resolution of
different future revisions, general MinIO/custom endpoint support,
or support for real-world arbitrary note sets.

No Yandex credentials or prepublished test bucket are available in
this PR. Therefore LIVE status must remain NOT RUN until a test owner
provides them OUTSIDE the repository and executes the command.
