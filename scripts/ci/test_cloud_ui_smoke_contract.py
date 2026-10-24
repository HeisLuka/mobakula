"""Pinned Android UI smoke selectors and S3 INTERNET runtime contract."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class CloudUiSmokeContract(unittest.TestCase):
    def test_existing_emulator_run_executes_cloud_overlay_ui(self):
        runner = (ROOT / "scripts/android/cold_reopen_probe.py").read_text("utf8")
        test = (ROOT / "android/app/src/androidTest/java/dev/heisluka/mobakula/"
                "S3CloudProviderUiTest.kt").read_text("utf8")
        self.assertIn("--include-ui", runner)
        self.assertIn("S3CloudProviderUiTest#yandexTileOpensOverlayWithoutClaimingSynchronization",
                      runner)
        self.assertIn("fun yandexTileOpensOverlayWithoutClaimingSynchronization()", test)
        self.assertIn("verify_instrumentation(cloud_ui)", runner)
        self.assertIn("cloud_s3_sheet", test)
        self.assertIn("cloud_yandex_tile", test)
        self.assertIn("Синхронизировано", test)

    def test_runtime_s3_permission_matches_installed_manifest(self):
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text("utf8")
        smoke = (ROOT / "android/app/src/androidTest/java/dev/heisluka/mobakula/"
                 "NativeNotesRoundTripTest.kt").read_text("utf8")
        runner = (ROOT / "scripts/android/cold_reopen_probe.py").read_text("utf8")
        self.assertIn("android.permission.INTERNET", manifest)
        self.assertIn("PackageManager.PERMISSION_GRANTED", smoke)
        self.assertNotIn("PackageManager.PERMISSION_DENIED", smoke)
        self.assertIn("roundTrip_andS3NetworkPermission", smoke)
        self.assertIn("roundTrip_andS3NetworkPermission", runner)
        self.assertNotIn("roundTrip_andNoInternetPermission", runner)


if __name__ == "__main__":
    unittest.main()
