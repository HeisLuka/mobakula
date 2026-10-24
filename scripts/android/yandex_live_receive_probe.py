#!/usr/bin/env python3
"""Opt-in LIVE Yandex S3 -> installed debug Android Akula acceptance.

NO cloud write operations. Only reads through the Android production S3
adapter. Requires a separate disposable emulator, app+androidTest APK,
a Core V1 test fixture already published in a dedicated Yandex test bucket,
and the invitation password in AKULA_YANDEX_INVITE_PASSWORD. Never pass a
password, encrypted invitation, or keys in ADB instrumentation arguments.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
PUBLISHER = ROOT / "scripts/s3/publish_yandex_fixture.py"
APP = "dev.heisluka.mobakula"
RUNNER = APP + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = APP + ".S3LiveReceiveDeviceTest"
PRIVATE_STAGE = "no_backup/s3-live-fixture"
RECEIPT = "akula_s3_live"


def load_validator():
    spec = importlib.util.spec_from_file_location("safe_s3_fixture", PUBLISHER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def is_disposable_emulator(properties):
    return properties.get("ro.kernel.qemu", "").strip() == "1"


def check_instrumentation(text, expected):
    if ("FAILURES!!!" in text or "INSTRUMENTATION_FAILED" in text or
            len(re.findall(r"(?m)^OK \(1 test\)\s*$", text)) != 1):
        raise RuntimeError("Live Android instrumentation did not pass exactly one test")
    status = re.escape(RECEIPT + "=" + expected)
    if len(re.findall(r"(?m)^INSTRUMENTATION_STATUS: " + status + r"\s*$", text)) != 1:
        raise RuntimeError("Live Android instrumentation lacks exact expected receipt")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path)
    parser.add_argument("--serial", help="Explicit ADB serial for the disposable emulator")
    parser.add_argument("--ack-disposable-emulator", action="store_true",
                        help="I authorize force-stops and test notes on this emulator")
    parser.add_argument("--ack-test-import", action="store_true",
                        help="I authorize receiving ONE untrusted test Markdown note")
    args = parser.parse_args()
    if not (args.ack_disposable_emulator and args.ack_test_import):
        parser.error("Both explicit consent flags are required")
    prefix = ["adb"] + (["-s", args.serial] if args.serial else [])

    def adb(*items, input_data=None, timeout=120):
        command = prefix + list(items)
        reply = subprocess.run(
            command, input=input_data, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, timeout=timeout, check=False,
        )
        if reply.returncode != 0:
            raise RuntimeError("ADB operation rejected; private test output suppressed")
        return reply.stdout.decode("utf8", errors="replace")

    try:
        root = args.fixture.resolve(strict=True)
        publisher = load_validator()
        bucket, checked = publisher.validate_fixture(root)
        if len(checked) != 5 or not bucket.startswith("akula-e2e-"):
            raise RuntimeError("Not a dedicated Core V1 acceptance graph")
        password = os.environ.get("AKULA_YANDEX_INVITE_PASSWORD")
        if password is None or not 20 <= len(password.encode("utf8")) <= 1024:
            raise RuntimeError("Missing/invalid test invitation password environment variable")
        if not is_disposable_emulator({
            "ro.kernel.qemu": adb("shell", "getprop", "ro.kernel.qemu").strip()
        }):
            raise RuntimeError("Live S3 receive permitted ONLY on a disposable emulator")
        for package in (APP, APP + ".test"):
            if "package:" not in adb("shell", "pm", "path", package):
                raise RuntimeError("Matching debug app and androidTest APK required")
        # run-as is accepted only for a debuggable app. No ordinary
        # user-device runtime or broad external storage is ever targeted.
        adb("shell", "run-as", APP, "sh", "-c",
            "mkdir -p " + PRIVATE_STAGE)

        def stage_private(relative, content):
            if relative not in ("index.json", "invite.akula-sync", "password.bin"):
                raise RuntimeError("Unexpected staging path")
            dest = PRIVATE_STAGE + "/" + relative
            adb("shell", "run-as", APP, "sh", "-c",
                "cat > " + dest, input_data=content, timeout=120)

        try:
            stage_private("index.json", (root / "index.json").read_bytes())
            stage_private("invite.akula-sync", (root / "invite.akula-sync").read_bytes())
            stage_private("password.bin", password.encode("utf8"))

            for method, expected in (
                ("importExactlyOneRemoteMarkdownAfterPinnedGenesis", "imported_once"),
                ("coldProcessReopenAndDuplicateReceiveAreFailClosed", "reopened_no_duplicate"),
            ):
                adb("shell", "am", "force-stop", APP)
                report = adb(
                    "shell", "am", "instrument", "-w", "-e", "class",
                    TEST_CLASS + "#" + method, RUNNER, timeout=600,
                )
                check_instrumentation(report, expected)
                print("PASS: live Yandex S3 Android " + expected, flush=True)
            print("LIVE receive PASS: File Gate + persisted history + no duplicate.")
            print("NOT a publisher trust proof or automatic S3 synchronization.")
            return 0
        finally:
            # Execute credential purge EVEN if import, HTTP or cold reopen
            # failed. Keep the disposable note and durable journal for audit.
            try:
                adb("shell", "am", "force-stop", APP)
                purged = adb(
                    "shell", "am", "instrument", "-w", "-e", "class",
                    TEST_CLASS + "#purgeTestS3Credentials", RUNNER, timeout=300,
                )
                check_instrumentation(purged, "credentials_purged")
                print("PASS: Android test S3 invitation/Keystore credentials purged",
                      flush=True)
            finally:
                # Even if Android Keystore is inaccessible, delete plaintext
                # staged password and invitation; NEVER log their contents.
                adb("shell", "run-as", APP, "sh", "-c",
                    "rm -f " + PRIVATE_STAGE + "/password.bin " +
                    PRIVATE_STAGE + "/invite.akula-sync " +
                    PRIVATE_STAGE + "/index.json")
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired):
        print("FAIL: live Yandex S3 Android acceptance not proven. "
              "Inspect CI/device receipts without sharing credentials.",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
