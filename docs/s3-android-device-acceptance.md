# Android S4d5 - device runtime acceptance gates for manual S3 receive

This is a separate, stacked verification gate on S4d4 / PR #42.
It is NOT evidence that a real desktop S3 vault has been imported yet.

## Why

The existing NativeNotesRoundTripTest expected INTERNET to be denied,
but the installed app now explicitly needs it for signed HTTPS S3 reads.
Mobile gate compiled instrumentation without executing it, so this old
assertion was invisible to the green APK CI. This PR corrects the
permission contract and makes S3 runtime security tests discoverable.

## Required Android runtime checks

Run ONLY against a disposable emulator or explicitly approved test phone
with the matching debug app and androidTest APK, never real production notes.
Existing cold_reopen_probe.py includes File Gate/history/process restart
and real Compose UI note save/reopen tests. New --include-s3-guards also runs:

- AndroidKeystore-backed checkpoint and AtomicFile persistence;
- generation rollback, equal-generation fork, foreign scope and stale
  compare-and-set refusal, while leaving the last valid anchor intact;
- tampered AES-GCM ciphertext refusal, not treated as a fresh S3 enrollment;
- checkpoint file deletion with retained Keystore key refusal;
- a real JNI invocation that rejects an invalid encrypted invitation,
  refuses journal reads without an authenticated temporary session, and
  refuses a note import without an allowlisted, rooted S3 session;
- previously existing S3 encrypted invitation storage and tamper checks.

The new Python CI policy-regression test pins the exact instrumentation
selector names and verifies the Manifest/INTERNET contract. It cannot
substitute for execution on a device.

## Execution

Normal pull-request Mobile gate tests Rust, both JNI ABIs, Kotlin JVM,
APK and instrumentation *compilation*. It does not run an emulator.

The opt-in workflow_dispatch input android_runtime=true enables the
hardware-accelerated emulator on a runner with readable/writable /dev/kvm.
The device runner executes:

  python3 scripts/android/cold_reopen_probe.py --ack-force-stop --include-smoke --include-ui --include-s3-guards

A runner without /dev/kvm is explicitly reported as an environment
failure, NOT as a product failure or device acceptance. Do not re-run
blindly on the same non-KVM environment.

## Separate still-required product E2E

1. Produce a disposable, nonempty one-note V1 .akula-sync object graph
   from a *real desktop Akula* against test MinIO or Yandex S3.
2. Pair the installed Android APK with its test invitation. Check S3,
   explicitly pin current history, then check S3 again.
3. Select exactly one current Markdown revision, confirm untrusted external
   source import, verify it appears in the normal Android note list and
   opens in the editor with identical BLAKE3 body.
4. Force-stop the app, restart it, verify note and HistoryService survive;
   importing the same DocumentId must never create a duplicate.
5. Inject 403/404/412, modified root/ETag, corrupted blob, local path
   collision, interrupted File Gate write and SQLite promotion. The UI must
   remain HOLD/Needs Review where appropriate, never claim synced.
6. Verify the flow using a signed, upgradeable APK; test real-device
   storage and Keystore across app update.

Never use production cloud credentials or real private user notes for
these experiments. A green compile-only CI is not an E2E S3 approval.
