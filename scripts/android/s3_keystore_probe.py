#!/usr/bin/env python3
"""Fail-closed real Android Keystore test on a disposable emulator only.

Invoked by the exact-branch temporary proof workflow; not a shipping API.
"""
import re
import subprocess
import sys

command = [
    "adb", "shell", "am", "instrument", "-w", "-r",
    "-e", "class", "dev.heisluka.mobakula.S3ProtectedInviteStoreTest",
    "dev.heisluka.mobakula.test/androidx.test.runner.AndroidJUnitRunner",
]
try:
    result = subprocess.run(command, text=True, capture_output=True, timeout=180, check=False)
except subprocess.TimeoutExpired:
    print("FAIL: Keystore instrumentation exceeded bounded timeout", file=sys.stderr)
    raise SystemExit(1)
print(result.stdout)
if result.stderr:
    print(result.stderr, file=sys.stderr)
if result.returncode != 0 or not re.search(r"\bOK\s+\(3 tests\)", result.stdout):
    print("FAIL: required Android Keystore 3-test proof missing", file=sys.stderr)
    raise SystemExit(1)
if re.search(r"\bFAILURES!!!|INSTRUMENTATION_FAILED|FATAL EXCEPTION", result.stdout):
    print("FAIL: explicit instrumentation failure", file=sys.stderr)
    raise SystemExit(1)
print("PASS: Android Keystore sealed invitation survives recreation and rejects tampering")
