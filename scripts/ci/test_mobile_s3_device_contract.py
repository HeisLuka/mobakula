"""Static contract for the opt-in Android S3 device acceptance smoke.

These checks DO NOT replace running instrumentation on an Android device.
They prevent silently compiling an outdated test selector or denying the
INTERNET permission that the now read-enabled mobile app requires.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


def read(relpath: str) -> str:
    return (ROOT / relpath).read_text(encoding="utf-8")


class S3AndroidRuntimeContractTests(unittest.TestCase):
    def test_network_permission_matches_runtime_note_smoke(self):
        manifest = read("android/app/src/main/AndroidManifest.xml")
        native = read(
            "android/app/src/androidTest/java/dev/heisluka/mobakula/"
            "NativeNotesRoundTripTest.kt"
        )
        runner = read("scripts/android/cold_reopen_probe.py")
        self.assertIn('android.permission.INTERNET', manifest)
        self.assertIn('PackageManager.PERMISSION_GRANTED', native)
        self.assertIn('fun roundTrip_andS3NetworkPermission()', native)
        self.assertIn(
            "NativeNotesRoundTripTest#roundTrip_andS3NetworkPermission", runner
        )
        self.assertNotIn("roundTrip_andNoInternetPermission", runner + native)

    def test_keystore_and_negative_jni_device_probes_are_selected(self):
        runner = read("scripts/android/cold_reopen_probe.py")
        checkpoint = read(
            "android/app/src/androidTest/java/dev/heisluka/mobakula/"
            "S3CheckpointDeviceTest.kt"
        )
        guard = read(
            "android/app/src/androidTest/java/dev/heisluka/mobakula/"
            "S3JniFailClosedDeviceTest.kt"
        )
        self.assertIn("--include-s3-guards", runner)
        for method in (
            "persistsSealedCheckpointAndEnforcesCompareAndSet",
            "tamperedCiphertextCannotBeReadOrMistakenForFirstObservation",
            "missingSealedFileWithExistingKeystoreKeyIsNotFreshEnrollment",
        ):
            self.assertIn("fun " + method, checkpoint)
            self.assertIn('#' + method, runner)
        self.assertIn(
            "fun unverifiedSessionsCannotReadBindingsOrCreateNotes", guard
        )
        self.assertIn(
            "#unverifiedSessionsCannotReadBindingsOrCreateNotes", runner
        )

    def test_github_runtime_smoke_remains_explicitly_opt_in(self):
        ci = read(".github/workflows/ci.yml")
        self.assertIn("workflow_dispatch:", ci)
        self.assertIn("android_runtime:", ci)
        self.assertIn("default: false", ci)
        self.assertIn("--include-s3-guards", ci)
        self.assertIn("android-runtime-acceptance", ci)
        self.assertIn("PR_RUNTIME_OPT_IN", ci)
        self.assertIn("if: steps.runtime.outputs.run == 'true'", ci)


if __name__ == "__main__":
    unittest.main()
