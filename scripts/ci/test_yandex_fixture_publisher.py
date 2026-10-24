"""No-cloud acceptance tests for the explicit Yandex Core V1 publisher."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/s3/publish_yandex_fixture.py"
spec = importlib.util.spec_from_file_location("yandex_fixture", SCRIPT)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)

VAULT = "11111111-2222-4333-8444-555555555555"
REV = "22222222-2222-4222-8222-222222222222"
SHARD = "33333333-3333-4333-8333-333333333333"
MANIFEST = "44444444-4444-4444-8444-444444444444"
PREFIX = "akula-e2e/device-acceptance"


class FixturePublisherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        base = f"{PREFIX}/v1/vaults/{VAULT}"
        self.keys = [
            f"{base}/blobs/{'a' * 64}",
            f"{base}/revisions/{REV}.json",
            f"{base}/manifests/{SHARD}.json",
            f"{base}/manifests/{MANIFEST}.json",
            f"{base}/refs/current.json",
        ]
        self.raw = {key: f"fixture payload {i}".encode("utf8")
                    for i, key in enumerate(self.keys)}
        self.data = {
            "schema": 1, "provider": "yandex",
            "bucket": "akula-e2e-disposable", "prefix": PREFIX,
            "vaultId": VAULT, "documentId": REV, "revisionId": REV,
            "genesisFingerprint": "c" * 64, "remoteOwnerVerified": False,
            "syncAuthorized": False,
            "objects": [
                {
                    "key": key, "sha256": hashlib.sha256(self.raw[key]).hexdigest(),
                    "bytes": len(self.raw[key]),
                }
                for key in self.keys
            ],
        }
        for key, body in self.raw.items():
            path = self.directory / "objects" / key
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(body)
        (self.directory / "invite.akula-sync").write_bytes(b"x" * 48)
        self.save()

    def save(self):
        (self.directory / "index.json").write_text(
            json.dumps(self.data), encoding="utf-8"
        )

    def test_validates_five_objects_without_network(self):
        bucket, checked = tool.validate_fixture(self.directory)
        self.assertEqual(bucket, "akula-e2e-disposable")
        self.assertEqual(len(checked), 5)
        self.assertTrue(checked[-1][0].endswith("/refs/current.json"))

    def test_only_dedicated_bucket_and_exact_scope(self):
        self.data["bucket"] = "production-notes"
        self.save()
        with self.assertRaises(ValueError):
            tool.validate_fixture(self.directory)
        self.data["bucket"] = "akula-e2e-disposable"
        self.data["objects"][-1]["key"] = "unscoped/current.json"
        self.save()
        with self.assertRaises(ValueError):
            tool.validate_fixture(self.directory)

    def test_tampered_file_and_remote_authority_are_rejected(self):
        target = self.directory / "objects" / self.keys[1]
        target.write_bytes(b"tampered")
        with self.assertRaises(ValueError):
            tool.validate_fixture(self.directory)
        target.write_bytes(self.raw[self.keys[1]])
        self.data["syncAuthorized"] = True
        self.save()
        with self.assertRaises(ValueError):
            tool.validate_fixture(self.directory)

    def test_upload_requires_if_none_match_and_verifies_readback_before_head(self):
        bucket, checked = tool.validate_fixture(self.directory)
        calls = []

        def fake_aws(argv, **_kwargs):
            action = argv[argv.index("s3api") + 1]
            key = argv[argv.index("--key") + 1]
            calls.append((action, key, argv))
            if action == "head-object":
                return SimpleNamespace(returncode=0, stdout='"etag123"\n')
            if action == "get-object":
                Path(argv[-1]).write_bytes(self.raw[key])
            return SimpleNamespace(returncode=0, stdout="")

        tool.publish_fixture(bucket, checked, {}, runner=fake_aws)
        writes = [(key, argv) for action, key, argv in calls
                  if action == "put-object"]
        self.assertEqual([key for key, _ in writes], self.keys)
        self.assertTrue(all(
            argv[argv.index("--if-none-match") + 1] == "*" for _, argv in writes
        ))
        self.assertEqual([action for action, _, _ in calls],
                         ["put-object", "head-object", "get-object"] * 5)
        self.assertTrue(all(
            "--if-match" in argv for action, _, argv in calls
            if action == "get-object"
        ))

    def test_bad_readback_stops_before_publishing_current_head(self):
        bucket, checked = tool.validate_fixture(self.directory)
        writes = []

        def tamper(argv, **_kwargs):
            action = argv[argv.index("s3api") + 1]
            key = argv[argv.index("--key") + 1]
            if action == "put-object":
                writes.append(key)
            elif action == "get-object":
                Path(argv[-1]).write_bytes(b"changed")
            return SimpleNamespace(
                returncode=0, stdout='"etag123"' if action == "head-object" else ""
            )

        with self.assertRaises(RuntimeError):
            tool.publish_fixture(bucket, checked, {}, runner=tamper)
        self.assertEqual(writes, self.keys[:1])


if __name__ == "__main__":
    unittest.main()
