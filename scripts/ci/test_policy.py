"""Pure-Python smoke tests for Mobakula's policy limits."""
from pathlib import Path
import tempfile
import unittest

from policy import check_paths


class PolicyTests(unittest.TestCase):
    def test_normal_script(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "small.rs"
            p.write_text("fn main() {}\n")
            self.assertEqual(check_paths([str(p)], bootstrap=False), [])

    def test_too_many_changed(self):
        errors = check_paths(["missing_file"] * 21, bootstrap=False)
        self.assertTrue(any("Changed files" in e for e in errors))

    def test_bootstrap_change_count_exception(self):
        errors = check_paths(["missing_file"] * 21, bootstrap=True)
        self.assertFalse(any("Changed files" in e for e in errors))

    def test_source_over_limit(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "huge.rs"
            p.write_text("x\n" * 501)
            self.assertTrue(any("Source lines" in e for e in check_paths([str(p)], bootstrap=False)))


if __name__ == "__main__":
    unittest.main()
