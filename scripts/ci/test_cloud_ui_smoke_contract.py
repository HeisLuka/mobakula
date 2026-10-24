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
        notes = (ROOT / "android/app/src/main/java/dev/heisluka/mobakula/"
                 "NotesScreen.kt").read_text("utf8")
        cloud = (ROOT / "android/app/src/main/java/dev/heisluka/mobakula/"
                 "CloudSourcesScreen.kt").read_text("utf8")
        files = (ROOT / "android/app/src/main/java/dev/heisluka/mobakula/"
                 "NotesFiles.kt").read_text("utf8")
        self.assertIn('tab == "cloud"', notes)
        self.assertIn('tab_cloud', notes)
        self.assertIn('tab_files', notes)
        self.assertIn('cloud_sources_screen', cloud)
        self.assertIn('S3InvitePreviewCard(', cloud)
        self.assertNotIn('S3InvitePreviewCard(', files)
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

    def test_real_emulator_is_explicitly_opted_in(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text("utf8")
        self.assertIn("android-runtime-acceptance", workflow)
        self.assertIn("PR_RUNTIME_OPT_IN", workflow)
        self.assertIn("steps.runtime.outputs.run == 'true'", workflow)


if __name__ == "__main__":
    unittest.main()
