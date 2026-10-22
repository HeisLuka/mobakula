#!/usr/bin/env python3
"""Check a verified Akula note across two actual Android app processes.

Requires installed app + androidTest APK (matching signing identity). This
script force-stops the target app but never clears data or uninstalls packages.
It uses an isolated no-backup probe vault, not the user's notes directory.
"""
from __future__ import annotations

import argparse
import subprocess
import sys

APP = "dev.heisluka.mobakula"
TEST_APP = APP + ".test"
RUNNER = TEST_APP + "/androidx.test.runner.AndroidJUnitRunner"
CLASS = APP + ".ColdReopenTwoProcessTest"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB device ID, required when multiple devices connected")
    parser.add_argument("--ack-force-stop", action="store_true",
                        help="I consent to force-stopping the running test app")
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

        def stage(phase: str, test_method: str) -> None:
            output = run(
                "shell", "am", "instrument", "-w",
                "-e", "class", CLASS + "#" + test_method,
                "-e", "akula_cold_phase", phase, RUNNER,
            )
            if "OK (1 test)" not in output:
                raise RuntimeError(f"{phase} did not pass exactly one test:\n{output[-3500:]}")
            print(f"PASS: {phase}")

        stage("prepare", "prepareVerifiedFileBeforeColdStop")
        run("shell", "am", "force-stop", APP, timeout=30)
        stage("verify", "verifySavedFileFromGenuinelyNewAndroidProcess")
        print("PASS: previously saved bytes, BLAKE3 revision, list and stale-hash rejection survived a cold process restart")
        return 0
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as cause:
        print(f"FAIL: {cause}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
