"""Safety and orchestration contracts for opt-in live Android S3 test."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
HOST = ROOT / "scripts/android/yandex_live_receive_probe.py"
DEVICE = ROOT / (
    "android/app/src/androidTest/java/dev/heisluka/mobakula/"
    "S3LiveReceiveDeviceTest.kt"
)
spec = importlib.util.spec_from_file_location("android_s3_live", HOST)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class S3LiveAcceptanceContract(unittest.TestCase):
    def test_accepts_only_actual_android_emulators(self):
        self.assertTrue(gate.is_disposable_emulator({"ro.kernel.qemu": "1"}))
        for source in ({}, {"ro.kernel.qemu": "0"},
                       {"ro.kernel.qemu": "emulator"},
                       {"ro.kernel.qemu": "1\nroot"}):
            self.assertFalse(gate.is_disposable_emulator(source))

    def test_requires_exact_pass_and_receipt(self):
        gate.check_instrumentation(
            "INSTRUMENTATION_STATUS: akula_s3_live=imported_once\n"
            "OK (1 test)\n", "imported_once")
        for bad in (
            "INSTRUMENTATION_STATUS: akula_s3_live=imported_once\nFAILURES!!!\n",
            "OK (0 tests)\n",
            "INSTRUMENTATION_STATUS: akula_s3_live=wrong\nOK (1 test)\n",
            "INSTRUMENTATION_STATUS: akula_s3_live=imported_once-forged\nOK (1 test)\n",
            "INSTRUMENTATION_STATUS: akula_s3_live=imported_once\nOK (1 test)\nOK (1 test)\n",
        ):
            with self.assertRaises(RuntimeError):
                gate.check_instrumentation(bad, "imported_once")

    def test_explicit_consent_and_app_private_secrets(self):
        source = HOST.read_text(encoding="utf8")
        for marker in (
            "--ack-disposable-emulator", "--ack-test-import",
            "purgeTestS3Credentials",
            "check_instrumentation(purged, \"credentials_purged\")",
            "AKULA_YANDEX_INVITE_PASSWORD", "ro.kernel.qemu",
            "run-as", "no_backup/s3-live-fixture",
            "password.bin", "invite.akula-sync",
            'adb("shell", "am", "force-stop", APP)',
        ):
            self.assertIn(marker, source)
        self.assertNotIn("AWS_SECRET_ACCESS_KEY", source)
        self.assertNotIn("put-object", source)
        self.assertNotIn("--password", source)
        self.assertNotIn("adb push", source)

    def test_real_android_network_import_has_cold_restart_and_no_duplicates(self):
        host = HOST.read_text(encoding="utf8")
        android = DEVICE.read_text(encoding="utf8")
        for method, status in (
            ("importExactlyOneRemoteMarkdownAfterPinnedGenesis", "imported_once"),
            ("coldProcessReopenAndDuplicateReceiveAreFailClosed",
             "reopened_no_duplicate"),
        ):
            self.assertIn(method, host)
            self.assertIn("fun " + method, android)
            self.assertIn(status, android)
        for marker in (
            "S3ManualImport.ExistingSourceBinding",
            "store.delete()",
            "fun purgeTestS3Credentials()",
            "record.getInt(\"originPid\"), Process.myPid()",
            "S3ReadOnlyProbe.run(store, checkpoint, selected)",
            "checkpoint.pinVerified",
            "S3ProtectedInviteStore",
            "S3CheckpointStore",
            "NativeBridge.inspectS3Invitation(envelope, password)",
            "identity.getString(\"bucket\") == meta.getString(\"bucket\")",
            "identity.getString(\"cloudVaultId\") == meta.getString(\"vaultId\")",
            "meta.getString(\"genesisFingerprint\")",
            "Vault: \" + meta.getString(\"vaultId\")",
            "assertEquals(1, after.length())",
            "assertEquals(1, listing.length())",
            "assertEquals(1, read.bindingScan.exact)",
            "rejectedAsExistingBinding",
            "credentials_purged",
            "ensureDisposableEmulator()",
        ):
            self.assertIn(marker, android)

    def test_normal_pr_gate_cannot_run_live_cloud_test_without_credentials(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text("utf8")
        self.assertNotIn("secrets.AKULA_YANDEX", workflow)
        self.assertNotIn("yandex_live_receive_probe.py", workflow)


if __name__ == "__main__":
    unittest.main()
