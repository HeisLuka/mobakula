"""Fail-closed provenance checks for a manually signed device-test APK."""
from __future__ import annotations

import json
from pathlib import Path
import re
import sys

WORKFLOW = ".github/workflows/ci.yml"
PACKAGE = "dev.heisluka.mobakula"
MAX_CODE = 2_100_000_000


def validate_run(run: dict, sha: str, repo: str) -> int:
    """Only a successful main push from this exact repository and SHA is trusted."""
    required = {
        "event": "push",
        "status": "completed",
        "conclusion": "success",
        "head_branch": "main",
        "head_sha": sha,
        "path": WORKFLOW,
    }
    for key, value in required.items():
        if run.get(key) != value:
            raise ValueError(f"Untrusted source run: {key} mismatch")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid 40-character commit SHA")
    if run.get("repository", {}).get("full_name") != repo:
        raise ValueError("Run is from a different repository")
    if run.get("head_repository", {}).get("full_name") != repo:
        raise ValueError("Source code comes from a different repository")
    code = run.get("run_number")
    if type(code) is not int or not (1 <= code <= MAX_CODE):
        raise ValueError("Invalid CI build versionCode")
    return code


def validate_apk_badging(output: str, expected_code: int) -> None:
    """aapt's manifest must agree with the source run and fixed app identity."""
    match = re.search(
        r"^package: name='([^']+)' versionCode='([0-9]+)'",
        output, re.MULTILINE,
    )
    if not match:
        raise ValueError("APK package/version not readable")
    if match.group(1) != PACKAGE:
        raise ValueError("APK has the wrong applicationId")
    if int(match.group(2)) != expected_code:
        raise ValueError("APK versionCode differs from CI source run")



def validate_main_ruleset(ruleset: dict) -> None:
    """Reject signing unless real GitHub main branch protection is active."""
    if ruleset.get("enforcement") != "active" or ruleset.get("target") != "branch":
        raise ValueError("main ruleset is not active")
    includes = ruleset.get("conditions", {}).get("ref_name", {}).get("include", [])
    if "~DEFAULT_BRANCH" not in includes and "refs/heads/main" not in includes:
        raise ValueError("main is not covered by this ruleset")
    rules = ruleset.get("rules", [])
    names = {item.get("type") for item in rules}
    for required in ("deletion", "non_fast_forward", "pull_request", "required_status_checks"):
        if required not in names:
            raise ValueError(f"main protection missing: {required}")
    checks = next(item for item in rules if item.get("type") == "required_status_checks")
    required_checks = checks.get("parameters", {}).get("required_status_checks", [])
    if not any(item.get("context") == "mobile-gate" for item in required_checks):
        raise ValueError("mobile-gate is not a required check")

def main(argv: list[str]) -> None:
    if len(argv) == 5 and argv[1] == "validate-run":
        run = json.loads(Path(argv[2]).read_text(encoding="utf-8"))
        print(validate_run(run, argv[3], argv[4]))
    elif len(argv) == 3 and argv[1] == "validate-ruleset":
        ruleset = json.loads(Path(argv[2]).read_text(encoding="utf-8"))
        validate_main_ruleset(ruleset)
        print("Main protection is active")
    elif len(argv) == 4 and argv[1] == "validate-apk":
        validate_apk_badging(
            Path(argv[2]).read_text(encoding="utf-8"), int(argv[3]),
        )
        print("APK package and version verified")
    else:
        raise ValueError("Usage: validate-run <json> <sha> <repo> | validate-apk <aapt-txt> <code>")


if __name__ == "__main__":
    try:
        main(sys.argv)
    except (ValueError, OSError, json.JSONDecodeError) as exc:
        sys.exit(f"SIGNING GATE FAIL: {exc}")
