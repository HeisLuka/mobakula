#!/usr/bin/env python3
"""Explicit, fail-closed Yandex test fixture upload; dry-run by default.

Only dedicated akula-e2e-* buckets and immutable Core-generated test graphs.
Never LIST, DELETE, replace objects, create buckets, or print cloud secrets.
Requires AWS CLI and narrowly scoped TEST credentials for --publish.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import uuid

HOST = "https://storage.yandexcloud.net"
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
PART = re.compile(r"[a-z0-9_-]{1,63}\Z")
BUCKET = re.compile(r"akula-e2e-[a-z0-9-]{1,50}\Z")
ORDER = ("blobs", "revisions", "manifests", "manifests", "refs")


def validate_fixture(directory):
    directory = Path(directory).resolve(strict=True)
    if not directory.is_dir() or (directory / "index.json").is_symlink():
        raise ValueError("unsafe fixture directory")
    data = (directory / "index.json").read_bytes()
    if not data or len(data) > 8192:
        raise ValueError("invalid index size")
    doc = json.loads(data)
    if set(doc) != {
        "schema", "provider", "bucket", "prefix", "vaultId", "documentId",
        "revisionId", "genesisFingerprint", "objects",
        "remoteOwnerVerified", "syncAuthorized"
    } or doc["schema"] != 1 or doc["provider"] != "yandex":
        raise ValueError("invalid test fixture identity")
    if doc["remoteOwnerVerified"] is not False or doc["syncAuthorized"] is not False:
        raise ValueError("remote trust or sync flag cannot be asserted")
    bucket, prefix, vault = doc["bucket"], doc["prefix"], doc["vaultId"]
    if not isinstance(bucket, str) or not BUCKET.fullmatch(bucket):
        raise ValueError("only dedicated akula-e2e-* test buckets")
    if not isinstance(prefix, str) or not prefix.startswith("akula-e2e/") or (
        any(not PART.fullmatch(p) for p in prefix.split("/"))
    ):
        raise ValueError("invalid isolated test prefix")
    for name in ("vaultId", "documentId", "revisionId"):
        value = doc[name]
        if not isinstance(value, str) or str(uuid.UUID(value)) != value:
            raise ValueError("noncanonical Core UUID")
    if not isinstance(doc["genesisFingerprint"], str) or not HEX64.fullmatch(
        doc["genesisFingerprint"]
    ):
        raise ValueError("invalid genesis")
    entries = doc["objects"]
    if not isinstance(entries, list) or len(entries) != 5:
        raise ValueError("expected one 5-object Core V1 graph")
    base = f"{prefix}/v1/vaults/{vault}/"
    checked = []
    for index, (entry, expected) in enumerate(zip(entries, ORDER)):
        if set(entry) != {"key", "sha256", "bytes"}:
            raise ValueError("unknown object metadata")
        key = entry["key"]
        if not isinstance(key, str) or not key.startswith(base):
            raise ValueError("foreign S3 namespace")
        subkey = key[len(base):]
        if not subkey.startswith(expected + "/"):
            raise ValueError("incorrect object upload order")
        name = subkey.split("/", 1)[1]
        if expected == "blobs":
            if not HEX64.fullmatch(name):
                raise ValueError("invalid content hash")
        elif expected == "refs":
            if name != "current.json" or index != 4:
                raise ValueError("current head must be last")
        elif not name.endswith(".json") or not is_uuid(name[:-5]):
            raise ValueError("invalid immutable Core ID")
        if not isinstance(entry["sha256"], str) or not HEX64.fullmatch(
            entry["sha256"]
        ) or type(entry["bytes"]) is not int or not 0 <= entry["bytes"] <= 1048576:
            raise ValueError("invalid object size or digest")
        file = directory / "objects" / key
        if not file.is_file() or file.is_symlink() or not file.resolve().is_relative_to(
            (directory / "objects").resolve()
        ):
            raise ValueError("missing/escaped local object")
        raw = file.read_bytes()
        if len(raw) != entry["bytes"] or hashlib.sha256(raw).hexdigest() != entry["sha256"]:
            raise ValueError("test fixture bytes modified")
        checked.append((key, file, raw))
    if len({x[0] for x in checked}) != 5:
        raise ValueError("duplicate object key")
    invite = directory / "invite.akula-sync"
    if not invite.is_file() or invite.is_symlink() or not 32 <= invite.stat().st_size <= 16384:
        raise ValueError("missing or invalid encrypted invitation")
    return bucket, checked


def is_uuid(text):
    try:
        return str(uuid.UUID(text)) == text
    except (ValueError, AttributeError, TypeError):
        return False


def aws_env():
    source = {
        "AWS_ACCESS_KEY_ID": os.environ.get("AKULA_YANDEX_TEST_ACCESS_KEY_ID", ""),
        "AWS_SECRET_ACCESS_KEY": os.environ.get("AKULA_YANDEX_TEST_SECRET_ACCESS_KEY", ""),
    }
    if not all(source.values()):
        raise ValueError("missing dedicated Yandex test credentials")
    env = dict(os.environ)
    env.update(source)
    env.update({
        "AWS_DEFAULT_REGION": "ru-central1", "AWS_REGION": "ru-central1",
        "AWS_EC2_METADATA_DISABLED": "true", "AWS_PAGER": "",
    })
    env.pop("AWS_SESSION_TOKEN", None)
    env.pop("AWS_PROFILE", None)
    return env


def invoke_aws(args, env, runner=subprocess.run):
    command = ["aws", "--endpoint-url", HOST, "--region", "ru-central1",
               "--no-cli-pager", "s3api", *args]
    p = runner(command, env=env, text=True, capture_output=True, check=False)
    if p.returncode:
        # Do not include raw error headers, command environment, or S3 responses.
        raise RuntimeError("Yandex test S3 request rejected (credentials, condition or network)")
    return p.stdout.strip()


def publish_fixture(bucket, checked, env, runner=subprocess.run):
    for index, (key, path, body) in enumerate(checked):
        if index == 4 and "/refs/current.json" not in key:
            raise ValueError("root must be published last")
        # Server-side conditional put: cannot replace an existing S3 object.
        invoke_aws(["put-object", "--bucket", bucket, "--key", key,
                    "--body", str(path), "--if-none-match", "*"], env, runner)
        etag = invoke_aws(["head-object", "--bucket", bucket, "--key", key,
                           "--query", "ETag", "--output", "text"], env, runner)
        if not re.fullmatch(r'"[A-Za-z0-9_.-]{1,128}"', etag):
            raise RuntimeError("Yandex test S3 returned an invalid ETag")
        with tempfile.TemporaryDirectory(prefix="akula-s3-readback-") as temp:
            target = str(Path(temp) / "readback")
            invoke_aws(["get-object", "--bucket", bucket, "--key", key,
                        "--if-match", etag, target], env, runner)
            if Path(target).read_bytes() != body:
                raise RuntimeError("Yandex test S3 readback mismatch; stop")
        print(f"VERIFIED immutable Core object {index + 1}/5 (no overwrite)")
    print("TEST HEAD published last. No cleanup or sync authority was granted.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path, help="fresh fixture output directory")
    parser.add_argument("--publish", action="store_true",
                        help="explicitly upload to a dedicated, existing test bucket")
    args = parser.parse_args()
    try:
        bucket, objects = validate_fixture(args.fixture)
        if not args.publish:
            print(f"VALIDATED: {len(objects)} local Core V1 test objects; no network writes.")
            return 0
        if shutil.which("aws") is None:
            raise ValueError("AWS CLI v2 is required for explicit publish")
        publish_fixture(bucket, objects, aws_env())
        return 0
    except (ValueError, OSError, RuntimeError, KeyError, TypeError, json.JSONDecodeError):
        print("FAIL: invalid/unsafe test fixture or Yandex S3 operation", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
