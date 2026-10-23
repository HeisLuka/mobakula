# Stable signing for test-device APK updates (NOT a store release)

## Why it exists

Regular `Mobile gate` uploads `mobakula-debug`: debug APK signed by a new ephemeral runner identity. Android permits an in-place update only with the **same applicationId**, the same signing certificate (or valid rotation), and a nondecreasing `versionCode`. Uninstalling an incompatible installation deletes its app-private notes. This is not fixed by renaming the APK.

This manual workflow re-signs **the exact successful main push artifact**; it does not rebuild, use PR code, publish to an app store, or expose a signing secret to normal CI. For main CI, Gradle takes `versionCode` from `GITHUB_RUN_NUMBER` of the sole `Mobile gate` workflow. Local builds use code 1.

## One-time owner setup

1. Before installing test builds with meaningful data, generate a **dedicated test-only long-lived Android key** on a secure owner machine; back it up offline. This must NOT be your future Play/RuStore signing key. Example on a machine with JDK:

   `keytool -genkeypair -v -storetype PKCS12 -keystore mobakula-device-test.p12 -alias mobakula-test -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Mobakula test"`

   Choose a strong password **locally**. Never paste the key or password into a chat, issue or PR.
2. Get the SHA-256 **signing certificate** fingerprint locally: `keytool -list -v -keystore mobakula-device-test.p12 -alias mobakula-test` (look for `SHA256:`). Preserve this out of band as the approved fingerprint.
3. **First activate the existing GitHub [main ruleset](https://github.com/HeisLuka/mobakula/rules/25669997)**. On 23 October 2026 the GitHub API was rechecked and reported **`enforcement=active`** with required PR, no force-push/deletion, and `mobile-gate`. This may change: recheck the [live main ruleset](https://github.com/HeisLuka/mobakula/rules/25669997) before adding credentials. The manual workflow independently fails closed if protection becomes disabled or weaker. The manual workflow **re-checks this ruleset via API and fails closed** if it is disabled or weakened.
4. In [GitHub Settings → Environments](https://github.com/HeisLuka/mobakula/settings/environments), configure **`device-test-signing`**. Restrict deployment branches to **`main`**; enable required approval if an independent reviewer is available. Require manual review of this workflow and key changes.
5. Add **environment secrets** `MOBAKULA_TEST_KEYSTORE_B64` (base64 of raw PKCS12 file, without line breaks) and `MOBAKULA_TEST_KEYSTORE_PASSWORD` (the local password). Add **environment variables** `MOBAKULA_TEST_KEY_ALIAS=mobakula-test` and `MOBAKULA_TEST_CERT_SHA256` (64 hex digits, colons optional). For bash owners, use `base64 -w0 mobakula-device-test.p12 | gh secret set -e device-test-signing MOBAKULA_TEST_KEYSTORE_B64` to avoid printing it. Do not put it in the repository.
6. First, obtain a passing [Mobile gate](https://github.com/HeisLuka/mobakula/actions/workflows/ci.yml) run triggered by a **push to current `main` after this feature is merged**; note its numeric run ID.
7. On GitHub Actions select **Sign device-test APK → Run workflow**, branch `main`, `source_run_id` from step 6. The signed archive `mobakula-device-test-signed` will include `mobakula-device-test.apk`, `SHA256SUMS.txt`, `provenance.txt` (retained 14 days).

Workflow fails closed if environment secrets are absent; the run is from a PR, a stale/non-current main commit, a different repo/workflow, or failed CI; the APK package/version differs; the key/cert differs from the approved fingerprint; or signature verification fails. Provenance records both the original CI APK SHA-256 and the signed output SHA-256.

## Acceptance: in-place update without losing notes

1. **First transition:** if the phone contains an ordinary CI debug APK signed by a different identity, there is NO supported in-place migration. Export every important note first; only on a clean test installation use the long-lived test identity. Never uninstall important data without a verified backup.
2. Install device-test signed build **A**. Create, edit, wait for `File Gate + HistoryService` verified autosave and export a backup.
3. After another **main** push, sign a new passed Mobile gate run **B** using the **same environment key**. Verify B's `version_code` is larger and its `signer_cert_sha256` is identical.
4. Update **over A**, without uninstalling: `adb install -r mobakula-device-test.apk`. If Android refuses, **stop**, do not run `adb uninstall`.
5. In airplane mode confirm exact text, note list and reopening after force-stop. Repeat saved/unsaved edit and import/export tests. Keep logs without sensitive note content.

**Limitations:** a private test key in protected environment is still a valuable signing secret. Only execute this workflow from reviewed main, with minimal permissions. These are not production/store builds; future Google Play or RuStore keys may be different. The signed test APK can be installed on multiple Android devices but has no store update channel.

## Physical Android preflight

Before installing a device-test signed APK on an ARM64 handset, run the **read-only**
[physical Alpha preflight](physical-alpha-preflight.md). It compares the signed
artifact against the owner's separately approved certificate fingerprint and, if
Akula is already installed, checks signing identity and increasing versionCode
without modifying the application. The phone acceptance checklist is in that
document. A PASS from this preflight alone is not physical UI acceptance.
