"""Fail-closed limits for Mobakula PR changes (standard library only)."""
from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess
import sys

BANNED = re.compile(r"(?i)(\.(apk|aab|apks|aar|so|jks|keystore|pem|p12|p8|zip)$|(^|/)\.env($|\.)|(^|/)local\.properties$)")
SOURCE = {".rs", ".kt", ".kts", ".java"}
BINARY_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico", ".pdf", ".bin"}
MAX_FILE = 1024 * 1024
MAX_BINARY = 256 * 1024
MAX_CHANGED = 20
MAX_DIFF = 800
ALLOW_BOOTSTRAP = "chore/repository-guardrails"


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def base_sha() -> str:
    candidate = os.environ.get("BASE_SHA", "")
    if candidate and re.fullmatch(r"[0-9a-f]{40}", candidate) and int(candidate, 16):
        try:
            return git("merge-base", candidate, "HEAD")
        except subprocess.CalledProcessError:
            pass
    # Main's first push has a zero before SHA; treat it as baseline.
    if os.environ.get("GITHUB_EVENT_NAME") == "push":
        return git("rev-parse", "HEAD")
    raise ValueError("No valid PR comparison base: fail closed")


def check_paths(paths: list[str], *, bootstrap: bool) -> list[str]:
    failures = []
    if len(paths) > MAX_CHANGED and not bootstrap:
        failures.append(f"Changed files {len(paths)} > {MAX_CHANGED}")
    workflow_files = [p for p in paths if p.startswith(".github/workflows/") and p.endswith((".yml", ".yaml"))]
    all_workflows = list(Path(".github/workflows").glob("*.yml")) + list(Path(".github/workflows").glob("*.yaml"))
    auto_workflows = [p for p in all_workflows if re.search(r"(?m)^\s*(pull_request|push):", p.read_text(encoding="utf-8"))]
    if len(auto_workflows) > 1:
        failures.append(f"More than one automatic PR/push workflow: {len(auto_workflows)}")
    for name in paths:
        p = Path(name)
        if not p.is_file():
            continue
        if BANNED.search(name):
            failures.append(f"Forbidden tracked asset: {name}")
        size = p.stat().st_size
        if size > MAX_FILE:
            failures.append(f"File >1MiB: {name}")
        if p.suffix.lower() in BINARY_EXTS and size > MAX_BINARY:
            failures.append(f"Binary >256KiB: {name}")
        if p.suffix.lower() in SOURCE or name.startswith(".github/workflows/"):
            limit = 800 if "test" in p.stem.lower() or "/test/" in name else 500
            if name.startswith(".github/workflows/"):
                limit = 300
            try:
                lines = len(p.read_text(encoding="utf-8").splitlines())
            except UnicodeDecodeError:
                failures.append(f"Non-text source: {name}")
                continue
            if lines > limit:
                failures.append(f"Source lines {lines} > {limit}: {name}")
    return failures


def main() -> int:
    try:
        base = base_sha()
        names = git("diff", "--name-only", "--diff-filter=ACMR", f"{base}..HEAD").splitlines()
        stat = git("diff", "--numstat", f"{base}..HEAD")
        count = 0
        for line in stat.splitlines():
            fields = line.split("\t", 2)
            if len(fields) >= 2 and fields[0].isdigit() and fields[1].isdigit():
                count += int(fields[0]) + int(fields[1])
        bootstrap = os.environ.get("PR_BRANCH", "") == ALLOW_BOOTSTRAP
        errors = check_paths(names, bootstrap=bootstrap)
        if count > MAX_DIFF and not bootstrap:
            errors.append(f"PR changed lines {count} > {MAX_DIFF}")
        # Validate full tracked tree, too: initial push must not skip banned assets.
        tracked = git("ls-files").splitlines()
        errors.extend(check_paths(tracked, bootstrap=True))
        if errors:
            for err in sorted(set(errors)):
                print("POLICY FAIL:", err, file=sys.stderr)
            return 1
        print(f"POLICY PASS: base={base[:10]}, files={len(names)}, churn={count}")
        return 0
    except (ValueError, subprocess.CalledProcessError, UnicodeError) as exc:
        print(f"POLICY ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
