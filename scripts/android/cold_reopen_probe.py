#!/usr/bin/env python3
"""Check a verified Akula note across two actual Android app processes.

Requires installed app + androidTest APK (matching signing identity). This
script force-stops the target app but never clears data or uninstalls packages.
It uses an isolated no-backup probe vault, not the user's notes directory.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys

APP = "dev.heisluka.mobakula"
TEST_APP = APP + ".test"
RUNNER = TEST_APP + "/androidx.test.runner.AndroidJUnitRunner"
CLASS = APP + ".ColdReopenTwoProcessTest"
SMOKE_CLASS = APP + ".NativeNotesRoundTripTest#roundTrip_andNoInternetPermission"
UI_CLASS = APP + ".UiNotesTwoProcessTest"


def verify_instrumentation(output: str, *, receipt: str | None = None,
                           receipt_key: str = "akula_cold_check") -> None:
    """adb's am instrument may return 0 for failures or skipped test selectors."""
    if not re.search(r"(?m)^OK \(1 test\)\s*$", output):
        raise RuntimeError(f"Instrumentation did not pass exactly one test:\n{output[-3500:]}")
    if receipt is not None and (receipt_key + "=" + receipt) not in output:
        raise RuntimeError(f"Instrumentation missing {receipt} phase evidence:\n{output[-3500:]}")



def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB device ID, required when multiple devices connected")
    parser.add_argument("--ack-force-stop", action="store_true",
                        help="I consent to force-stopping the running test app")
    parser.add_argument("--include-smoke", action="store_true",
                        help="Also run JNI/Core smoke in the separate no-backup test vault")
    parser.add_argument("--include-ui", action="store_true",
                        help="Run real Compose UI cold-reopen proof on a disposable emulator only")
    args = parser.parse_args()
    if not args.ack_force_stop:
        parser.error("Pass --ack-force-stop; this interrupts the test app.")
    prefix = ["adb"] + (["-s", args.serial] if args.serial else [])

    def run(*command: str, timeout: int = 180) -> str:
        result = subprocess.run(
            prefix + list(command), capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=timeout, check=False,
        )
        output = result.stdout + result.stderr
        if result.returncode != 0:
            raise RuntimeError(f"ADB command failed ({result.returncode}): {output[-2500:]}")
        return output

    try:
        for package in (APP, TEST_APP):
            if "package:" not in run("shell", "pm", "path", package, timeout=30):
                raise RuntimeError(f"{package} not installed: install matching app/androidTest APKs first")

        if args.include_smoke:
            smoke = run("shell", "am", "instrument", "-w",
                        "-e", "class", SMOKE_CLASS, RUNNER, timeout=300)
            verify_instrumentation(smoke)
            print("PASS: Kotlin -> JNI -> Core File Gate smoke", flush=True)

        def stage(phase: str, test_method: str, receipt: str) -> None:
            output = run(
                "shell", "am", "instrument", "-w",
                "-e", "class", CLASS + "#" + test_method,
                "-e", "akula_cold_phase", phase, RUNNER,
            )
            verify_instrumentation(output, receipt=receipt)
            print(f"PASS: {phase}", flush=True)

        run("shell", "am", "force-stop", APP, timeout=30)
        stage("prepare", "prepareVerifiedFileBeforeColdStop", "prepared")
        run("shell", "am", "force-stop", APP, timeout=30)
        stage("verify", "verifySavedFileFromGenuinelyNewAndroidProcess", "verified")
        print("PASS: previously saved bytes, BLAKE3 revision, list and stale-hash rejection survived a cold process restart")
        if args.include_ui:
            for phase, method, receipt in (
                ("prepare", "prepareNoteThroughUi", "ui_prepared"),
                ("verify", "reopenNoteThroughUi", "ui_verified"),
                ("first_input", "firstTypedCharacterKeepsPreviouslySavedText", "ui_first_input_preserved"),
                ("recreate", "recreateActivityRetainsPendingDraftAndResumesAutoSave", "ui_recreated"),
                ("recovery_prepare", "prepareUnsavedDraftForColdRecovery", "ui_recovery_prepared"),
                ("recovery_verify", "recoverDraftInNewProcessWithoutOverwritingFile", "ui_recovery_verified"),
            ):
                run("shell", "am", "force-stop", APP, timeout=30)
                output = run(
                    "shell", "am", "instrument", "-w",
                    "-e", "class", UI_CLASS + "#" + method,
                    "-e", "akula_ui_phase", phase, RUNNER, timeout=300,
                )
                verify_instrumentation(output, receipt=receipt, receipt_key="akula_ui_check")
                print(f"PASS: Compose UI {phase}", flush=True)
            print("PASS: UI create, edit, save and reopen across Android processes", flush=True)
            print("PASS: unsaved editor retained after Activity recreation; autosave resumed", flush=True)
            print("PASS: unsaved Unicode draft offered and restored across process restart", flush=True)
        return 0
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as cause:
        print(f"FAIL: {cause}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
