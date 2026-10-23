#!/usr/bin/env python3
"""Read-only preflight of a signed Akula APK against a physical Android device.

This tool NEVER installs, uninstalls, clears, force-stops, or writes app data.
It only reads package metadata, and pulls the installed APK to a local temp dir.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from zipfile import BadZipFile, ZipFile

PACKAGE = "dev.heisluka.mobakula"
APK = "mobakula-device-test.apk"
HEX64 = re.compile(r"[a-fA-F0-9]{64}\Z")


class PreflightError(ValueError):
    pass


def fingerprint(value: str) -> str:
    normalized = value.replace(":", "").strip().lower()
    if not HEX64.fullmatch(normalized):
        raise PreflightError("Invalid certificate SHA-256 fingerprint")
    return normalized


def properties(text: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for row in text.splitlines():
        if not row.strip():
            continue
        key, sep, value = row.partition("=")
        if not sep or not re.fullmatch(r"[a-z][a-z0-9_]*", key) or not value or key in result:
            raise PreflightError("Invalid or duplicated provenance field")
        result[key] = value.strip()
    return result


def badging(text: str) -> tuple[str, int, int]:
    header = re.search(r"(?m)^package: name='([^']+)' versionCode='([0-9]+)'", text)
    sdk = re.search(r"(?m)^sdkVersion:'([0-9]+)'", text)
    if not header or not sdk:
        raise PreflightError("APK identity, versionCode or minSdk not readable")
    return header.group(1), int(header.group(2)), int(sdk.group(1))


def signer(text: str) -> str:
    signatures = re.findall(
        r"(?m)^Signer #[0-9]+ certificate SHA-256 digest:\s*([a-fA-F0-9:]+)\s*$", text,
    )
    if len(signatures) != 1:
        raise PreflightError("Expected exactly one verified APK signer certificate")
    return fingerprint(signatures[0])


def validate_bundle(bundle: Path, expected_cert: str, badge: str, signature: str) -> int:
    apk = bundle / APK
    if not apk.is_file():
        raise PreflightError("Signed APK is missing from the bundle")
    details = properties((bundle / "provenance.txt").read_text(encoding="utf-8"))
    for key in ("source_run_id", "source_commit", "application_id", "version_code",
                "signer_cert_sha256", "input_debug_apk_sha256"):
        if key not in details:
            raise PreflightError(f"Missing provenance field: {key}")
    if not details["source_run_id"].isdigit() or int(details["source_run_id"]) == 0 or not re.fullmatch(
            r"[a-f0-9]{40}", details["source_commit"]):
        raise PreflightError("Invalid provenance source run or commit")
    if not HEX64.fullmatch(details["input_debug_apk_sha256"]):
        raise PreflightError("Invalid provenance source APK hash")
    if details["application_id"] != PACKAGE:
        raise PreflightError("Provenance applicationId mismatch")
    if not details["version_code"].isdigit():
        raise PreflightError("Invalid provenance versionCode")
    code = int(details["version_code"])
    if not 1 <= code <= 2_100_000_000:
        raise PreflightError("Invalid provenance versionCode range")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    sums = (bundle / "SHA256SUMS.txt").read_text(encoding="ascii").strip()
    match = re.fullmatch(r"([a-fA-F0-9]{64})  mobakula-device-test\.apk", sums)
    if not match or match.group(1).lower() != digest:
        raise PreflightError("Signed APK SHA-256 mismatch")
    if signer(signature) != fingerprint(expected_cert) or signer(signature) != fingerprint(
            details["signer_cert_sha256"]):
        raise PreflightError("APK signer does not match approved device-test certificate")
    name, apk_code, _ = badging(badge)
    if name != PACKAGE or apk_code != code:
        raise PreflightError("Signed APK package/version differs from provenance")
    try:
        with ZipFile(apk) as archive:
            if "lib/arm64-v8a/libmobakula_mobile.so" not in archive.namelist():
                raise PreflightError("Signed APK lacks ARM64 JNI library")
    except BadZipFile as exc:
        raise PreflightError("Signed APK ZIP is invalid") from exc
    return code


def validate_device(api_text: str, abis_text: str, qemu_text: str,
                    candidate_min_sdk: int) -> None:
    if qemu_text.strip() == "1":
        raise PreflightError("Physical phone required; emulator detected")
    if not api_text.strip().isdigit():
        raise PreflightError("Android API level not readable")
    if int(api_text.strip()) < candidate_min_sdk:
        raise PreflightError("Android version is below APK minSdk")
    if "arm64-v8a" not in abis_text.strip().split(","):
        raise PreflightError("Phone is not an ARM64-v8a Android device")


def validate_upgrade(incoming_code: int, expected_cert: str, installed_badge: str,
                     installed_signature: str) -> int:
    name, current_code, _ = badging(installed_badge)
    if name != PACKAGE:
        raise PreflightError("Installed package identity mismatch")
    if signer(installed_signature) != fingerprint(expected_cert):
        raise PreflightError("Different installed signing identity: do not uninstall or overwrite")
    if incoming_code <= current_code:
        raise PreflightError("APK versionCode must increase for an update")
    return current_code


def command(args: list[str], *, timeout: int = 35) -> str:
    try:
        proc = subprocess.run(args, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout, check=False)
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        raise PreflightError(f"Command unavailable or timed out: {args[0]}") from exc
    if proc.returncode != 0:
        raise PreflightError(f"Read-only command failed: {args[0]} (exit {proc.returncode})")
    return proc.stdout.strip()


def online_device(serial: str | None) -> str:
    output = command(["adb", "devices"])
    devices = [match.group(1) for row in output.splitlines()
               if (match := re.fullmatch(r"([^\s]+)\s+device\s*", row.strip()))]
    if serial is not None:
        if serial not in devices:
            raise PreflightError("Specified ADB device is not online")
        return serial
    if len(devices) != 1:
        raise PreflightError("Connect exactly one online phone or provide --serial")
    return devices[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True,
                        help="Unpacked mobakula-device-test-signed GitHub artifact")
    parser.add_argument("--expected-cert", required=True,
                        help="Owner-approved test signing certificate SHA-256 (not a secret)")
    parser.add_argument("--serial", help="ADB serial, only needed for multiple connected devices")
    args = parser.parse_args()
    try:
        apk = args.bundle / APK
        if not apk.is_file():
            raise PreflightError("Signed APK is missing from the bundle")
        badge = command(["aapt", "dump", "badging", str(apk)])
        sig = command(["apksigner", "verify", "--print-certs", str(apk)])
        incoming = validate_bundle(args.bundle, args.expected_cert, badge, sig)
        _, _, min_sdk = badging(badge)
        serial = online_device(args.serial)
        adb = ["adb", "-s", serial]
        validate_device(
            command(adb + ["shell", "getprop", "ro.build.version.sdk"]),
            command(adb + ["shell", "getprop", "ro.product.cpu.abilist"]),
            command(adb + ["shell", "getprop", "ro.kernel.qemu"]), min_sdk,
        )
        paths = command(adb + ["shell", "pm", "path", PACKAGE]).splitlines()
        if not paths:
            print(f"FRESH_INSTALL_METADATA_OK package={PACKAGE} versionCode={incoming}")
        else:
            if len(paths) != 1 or not paths[0].startswith("package:/"):
                raise PreflightError("Unexpected installed APK paths; refusing update judgment")
            with tempfile.TemporaryDirectory(prefix="akula-apk-readback-") as tmp:
                installed_apk = Path(tmp) / "installed-base.apk"
                command(adb + ["pull", paths[0][len("package:"):], str(installed_apk)],
                        timeout=90)
                old_badge = command(["aapt", "dump", "badging", str(installed_apk)])
                old_sig = command(["apksigner", "verify", "--print-certs", str(installed_apk)])
                previous = validate_upgrade(incoming, args.expected_cert, old_badge, old_sig)
            print(f"IN_PLACE_UPDATE_METADATA_OK package={PACKAGE} from={previous} to={incoming}")
        print("No installation or application data change was performed.")
        return 0
    except (PreflightError, OSError, UnicodeError) as exc:
        print(f"PREFLIGHT BLOCKED: {exc}", file=sys.stderr)
        print("Nothing was installed, removed or cleared. Do not uninstall to bypass this check.",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
