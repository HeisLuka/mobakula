# Android Alpha: physical phone acceptance without losing notes

The app/JNI cold-reopen and Compose UI round-trip passed on an accelerated API 35
x86_64 emulator. **A physical ARM64 handset has not passed acceptance.** This
procedure is about installing the actual Akula Android APK, not replacing the
application with a test script.

## Prerequisite: one stable signing identity

1. On the repository owner's machine, complete the protected **test-only** key and
   GitHub Environment setup in [device-test-signing.md](device-test-signing.md).
   Do not put a keystore, password, or backup of either into a chat, PR, or repo.
2. From current protected `main`, use a successful **push** `Mobile gate` run as
   the `source_run_id` input of the manual **Sign device-test APK** workflow.
   The signed workflow must finish SUCCESS; do not use an ordinary per-run debug
   artifact for data-bearing updates.
3. Download and unpack the `mobakula-device-test-signed` Actions artifact. Keep
   its three files together: `mobakula-device-test.apk`, `SHA256SUMS.txt`, and
   `provenance.txt`. Independently verify the expected signing certificate SHA-256
   against the fingerprint the owner stored outside the downloaded artifact.

No signed-A/B update is confirmed merely because the signing workflow exists.
The downloaded provenance file is not an independently authenticated GitHub
attestation; obtain it from the trusted workflow and check the certificate
against the separate owner fingerprint.

## Read-only device gate

On a local computer with Android SDK `adb`, `aapt`, and `apksigner` in `PATH`,
connect an authorized ARM64 Android phone via USB debugging. On that computer:

```sh
python3 scripts/android/device_preflight.py \
  --bundle /path/to/unpacked/mobakula-device-test-signed \
  --expected-cert YOUR_APPROVED_TEST_CERT_SHA256
```

Use `--serial DEVICE_SERIAL` only if multiple ADB devices are connected. The
approved certificate fingerprint is public metadata, **not** a private key.

This preflight checks the signed archive's SHA-256, manifest `applicationId`
and `versionCode`, JNI ARM64 library, certificate against the independent owner
fingerprint, Android API/ABI, and any installed Akula APK's signing identity and
version. If Akula is already installed, it reads its APK with `adb pull` into
an automatically removed **local** temporary directory. It does **not** read
private notes, change the phone, or run `adb install`, `uninstall`, `pm clear`
or `force-stop`.

- `FRESH_INSTALL_METADATA_OK`: Akula is not installed. This is only a metadata
  preflight for a new install, not a claim that installation or UI works.
- `IN_PLACE_UPDATE_METADATA_OK`: an installed APK has the same signing cert and
  a lower `versionCode`. This does **not** guarantee Android will install it.
- `PREFLIGHT BLOCKED`: stop. Never uninstall a data-bearing Akula to make an
  incompatible APK install. Export and independently verify any important notes.

## Physical phone acceptance (manual, real app)

After a passing preflight, **only the owner/tester** decides whether to install
via Android or `adb install -r /path/to/mobakula-device-test.apk`. Do not clear app data.
On a clean test setup, record the phone model, Android version and application
build (but no device serial or note contents in public CI logs). Verify:

1. Open Akula, create an offline Markdown note with Russian, emoji, and multiple
   paragraphs. Save until the app reports verified file+history; leave and reopen.
2. Edit with the actual system keyboard, select/copy/paste text, switch input
   language, dismiss/reopen keyboard and rotate portrait/landscape. Verify caret,
   selection and text remain intact. Check a dirty edit survives rotation.
3. Save, force-stop/relaunch, enable airplane mode, then reopen the same note;
   compare exact bytes with the separately exported `.md` file. **Do not claim
   unsaved-draft recovery**: this feature is not implemented.
4. Import a UTF-8 `.md`, export it, and compare the external bytes. Reject binary,
   invalid UTF-8 or a file over 1 MiB without creating a truncated note.
5. For updates, repeat with signed build B from a newer successful `main` push,
   using the **same signing identity** and higher `versionCode`. Run preflight
   against A before attempting the in-place update. If Android rejects the update,
   stop rather than uninstalling. Verify the old note and its history survive B.

Mark physical acceptance PASS only after recorded handset evidence for these
steps. The metadata gate and emulator CI alone do not satisfy that requirement.
