import copy
import unittest

from device_sign_source import validate_apk_badging, validate_run


def valid_run() -> dict:
    return {
        "event": "push",
        "status": "completed",
        "conclusion": "success",
        "head_branch": "main",
        "head_sha": "a" * 40,
        "path": ".github/workflows/ci.yml",
        "run_number": 36,
        "repository": {"full_name": "HeisLuka/mobakula"},
        "head_repository": {"full_name": "HeisLuka/mobakula"},
    }


class SigningProvenanceTests(unittest.TestCase):
    def test_accepts_only_matching_main_success(self):
        self.assertEqual(validate_run(valid_run(), "a" * 40, "HeisLuka/mobakula"), 36)

    def test_rejects_pr_or_failed_run(self):
        for key, value in [("event", "pull_request"), ("conclusion", "failure"),
                           ("status", "in_progress"), ("head_branch", "feature"),
                           ("path", ".github/workflows/evil.yml")]:
            with self.subTest(key=key):
                data = valid_run()
                data[key] = value
                with self.assertRaises(ValueError):
                    validate_run(data, "a" * 40, "HeisLuka/mobakula")

    def test_rejects_sha_or_repo_mismatch(self):
        with self.assertRaises(ValueError):
            validate_run(valid_run(), "b" * 40, "HeisLuka/mobakula")
        data = valid_run()
        data["head_repository"]["full_name"] = "Somewhere/else"
        with self.assertRaises(ValueError):
            validate_run(data, "a" * 40, "HeisLuka/mobakula")

    def test_rejects_invalid_version_codes(self):
        for code in [0, -1, 2_100_000_001, "36", True]:
            with self.subTest(code=code):
                data = valid_run()
                data["run_number"] = code
                with self.assertRaises(ValueError):
                    validate_run(data, "a" * 40, "HeisLuka/mobakula")

    def test_apk_package_and_monotonic_code(self):
        info = "package: name='dev.heisluka.mobakula' versionCode='36' versionName='0.1.0-dev'"
        validate_apk_badging(info, 36)
        with self.assertRaises(ValueError):
            validate_apk_badging(info, 35)
        with self.assertRaises(ValueError):
            validate_apk_badging(info.replace("dev.heisluka.mobakula", "evil.app"), 36)


if __name__ == "__main__":
    unittest.main()
