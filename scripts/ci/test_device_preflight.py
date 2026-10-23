"""Offline tests for the physical Android signed-update preflight."""
from importlib.util import module_from_spec, spec_from_file_location
import hashlib
import io
from contextlib import redirect_stdout
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

SOURCE = Path(__file__).resolve().parents[1] / "android" / "device_preflight.py"
spec = spec_from_file_location("device_preflight", SOURCE)
pre = module_from_spec(spec)
spec.loader.exec_module(pre)
CERT = "a" * 64
BADGE = "package: name='dev.heisluka.mobakula' versionCode='42' versionName='dev'\nsdkVersion:'26'"
OLD_BADGE = BADGE.replace("versionCode='42'", "versionCode='41'")
SIG = "Signer #1 certificate SHA-256 digest: " + CERT


def bundle(root: Path) -> None:
    apk = root / pre.APK
    with ZipFile(apk, "w") as f:
        f.writestr("lib/arm64-v8a/libmobakula_mobile.so", b"fake native")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (root / "SHA256SUMS.txt").write_text(f"{digest}  {pre.APK}\n", encoding="ascii")
    (root / "provenance.txt").write_text(
        "source_run_id=39973633389\nsource_commit=" + "c" * 40 +
        "\ninput_debug_apk_sha256=" + "b" * 64 +
        "\napplication_id=dev.heisluka.mobakula\nversion_code=42\n"
        f"signer_cert_sha256={CERT}\n", encoding="utf-8")


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        bundle(self.root)

    def validate(self):
        return pre.validate_bundle(self.root, CERT, BADGE, SIG)

    def test_signed_bundle_agrees_with_out_of_band_certificate(self):
        self.assertEqual(self.validate(), 42)

    def test_refuses_modified_apk_and_checksums(self):
        with (self.root / pre.APK).open("ab") as stream:
            stream.write(b"tamper")
        with self.assertRaisesRegex(pre.PreflightError, "SHA-256 mismatch"):
            self.validate()

    def test_refuses_unapproved_or_ambiguous_signer(self):
        for expected, actual in [("b" * 64, SIG), (CERT, SIG + "\n" + SIG)]:
            with self.subTest(expected=expected, actual=actual):
                with self.assertRaises(pre.PreflightError):
                    pre.validate_bundle(self.root, expected, BADGE, actual)

    def test_rejects_wrong_package_version_or_provenance(self):
        invalid = [BADGE.replace("dev.heisluka.mobakula", "other.app"), OLD_BADGE]
        for text in invalid:
            with self.subTest(text=text):
                with self.assertRaises(pre.PreflightError):
                    pre.validate_bundle(self.root, CERT, text, SIG)
        with (self.root / "provenance.txt").open("a") as stream:
            stream.write("source_run_id=1\n")
        with self.assertRaisesRegex(pre.PreflightError, "duplicated"):
            self.validate()

    def test_refuses_apk_missing_arm64_jni(self):
        apk = self.root / pre.APK
        with ZipFile(apk, "w") as f:
            f.writestr("lib/x86_64/libmobakula_mobile.so", b"wrong ABI")
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        (self.root / "SHA256SUMS.txt").write_text(f"{digest}  {pre.APK}")
        with self.assertRaisesRegex(pre.PreflightError, "ARM64"):
            self.validate()

    def test_bad_checksum_missing_min_sdk_and_weak_fingerprint(self):
        with self.assertRaises(pre.PreflightError):
            pre.fingerprint("1234")
        with self.assertRaises(pre.PreflightError):
            pre.badging(BADGE.split("\n")[0])
        (self.root / "SHA256SUMS.txt").write_text("corrupted")
        with self.assertRaises(pre.PreflightError):
            self.validate()


class DeviceTests(unittest.TestCase):
    def test_only_arm64_android_and_sufficient_api_accepted(self):
        pre.validate_device("35", "arm64-v8a,armeabi-v7a", "0", 26)
        for api, abi, qemu in [("25", "arm64-v8a", "0"),
                               ("35", "x86_64", "0"),
                               ("35", "arm64-v8a", "1"),
                               ("unknown", "arm64-v8a", "0")]:
            with self.subTest(api=api, abi=abi, qemu=qemu):
                with self.assertRaises(pre.PreflightError):
                    pre.validate_device(api, abi, qemu, 26)

    def test_update_demands_same_signer_and_higher_version(self):
        self.assertEqual(pre.validate_upgrade(42, CERT, OLD_BADGE, SIG), 41)
        for version, cert in [(40, SIG), (41, SIG), (43, "Signer #1 certificate SHA-256 digest: " + "b" * 64)]:
            with self.subTest(version=version, cert=cert):
                with self.assertRaises(pre.PreflightError):
                    pre.validate_upgrade(version, CERT, OLD_BADGE, cert)

    def test_read_only_fresh_install_preflight_executes_no_mutation(self):
        with tempfile.TemporaryDirectory() as path:
            root = Path(path)
            bundle(root)
            seen = []

            def fake_command(args, *, timeout=35):
                seen.append(args)
                if args[0] == "aapt":
                    return BADGE
                if args[0] == "apksigner":
                    return SIG
                if args == ["adb", "devices"]:
                    return "List of devices attached\nphone1\tdevice\n"
                if args[-2:] == ["getprop", "ro.build.version.sdk"]:
                    return "35"
                if args[-2:] == ["getprop", "ro.product.cpu.abilist"]:
                    return "arm64-v8a,armeabi-v7a"
                if args[-2:] == ["getprop", "ro.kernel.qemu"]:
                    return "0"
                if args[-3:] == ["pm", "path", pre.PACKAGE]:
                    return ""
                raise AssertionError("Unexpected command " + repr(args))

            stdout = io.StringIO()
            argv = ["preflight", "--bundle", path, "--expected-cert", CERT]
            with patch.object(sys, "argv", argv), patch.object(pre, "command", side_effect=fake_command):
                with redirect_stdout(stdout):
                    self.assertEqual(pre.main(), 0)
            self.assertIn("FRESH_INSTALL_METADATA_OK", stdout.getvalue())
            self.assertTrue(all(not set(cmd) & {"install", "uninstall", "clear", "force-stop"}
                                for cmd in seen))

    def test_read_only_existing_installation_checks_signer_and_version(self):
        with tempfile.TemporaryDirectory() as path:
            bundle(Path(path))
            seen = []

            def fake_command(args, *, timeout=35):
                seen.append(args)
                if args[0] == "aapt":
                    return OLD_BADGE if args[-1].endswith("installed-base.apk") else BADGE
                if args[0] == "apksigner":
                    return SIG
                if args == ["adb", "devices"]:
                    return "List of devices attached\nphone1\tdevice\n"
                if args[-2:] == ["getprop", "ro.build.version.sdk"]:
                    return "35"
                if args[-2:] == ["getprop", "ro.product.cpu.abilist"]:
                    return "arm64-v8a,armeabi-v7a"
                if args[-2:] == ["getprop", "ro.kernel.qemu"]:
                    return "0"
                if args[-3:] == ["pm", "path", pre.PACKAGE]:
                    return "package:/data/app/base.apk"
                if "pull" in args:
                    Path(args[-1]).write_bytes(b"local test fixture")
                    return "1 file pulled"
                raise AssertionError("Unexpected command " + repr(args))

            output = io.StringIO()
            argv = ["preflight", "--bundle", path, "--expected-cert", CERT]
            with patch.object(sys, "argv", argv), patch.object(pre, "command", side_effect=fake_command):
                with redirect_stdout(output):
                    self.assertEqual(pre.main(), 0)
            self.assertIn("IN_PLACE_UPDATE_METADATA_OK", output.getvalue())
            self.assertTrue(any("pull" in cmd for cmd in seen))
            self.assertTrue(all(not set(cmd) & {"install", "uninstall", "clear", "force-stop"}
                                for cmd in seen))

    def test_device_selection_fails_closed_for_many_or_offline(self):
        with patch.object(pre, "command", return_value="List of devices attached\nphone1\tdevice\nphone2\tdevice\n"):
            with self.assertRaisesRegex(pre.PreflightError, "exactly one"):
                pre.online_device(None)
            self.assertEqual(pre.online_device("phone1"), "phone1")
        with patch.object(pre, "command", return_value="List of devices attached\nphone1\toffline\n"):
            with self.assertRaises(pre.PreflightError):
                pre.online_device("phone1")


if __name__ == "__main__":
    unittest.main()
