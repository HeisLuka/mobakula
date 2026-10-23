"""Offline regressions for the Android emulator instrumentation output gate."""
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "android" / "cold_reopen_probe.py"
spec = spec_from_file_location("cold_reopen_probe", SCRIPT)
probe = module_from_spec(spec)
spec.loader.exec_module(probe)


class InstrumentationVerdictTests(unittest.TestCase):
    def test_explicit_single_success(self):
        probe.verify_instrumentation("INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n")

    def test_both_receipts_required_for_two_process_stages(self):
        for stage in ("prepared", "verified"):
            with self.subTest(stage=stage):
                probe.verify_instrumentation(
                    f"INSTRUMENTATION_STATUS: akula_cold_check={stage}\nOK (1 test)\n",
                    receipt=stage,
                )
        with self.assertRaisesRegex(RuntimeError, "phase evidence"):
            probe.verify_instrumentation("OK (1 test)\n", receipt="verified")

    def test_ui_receipts_must_not_be_inferred_from_ok_only(self):
        for receipt in ("ui_prepared", "ui_verified", "ui_recreated",
                        "ui_recovery_prepared", "ui_recovery_verified"):
            with self.subTest(receipt=receipt):
                with self.assertRaisesRegex(RuntimeError, "phase evidence"):
                    probe.verify_instrumentation("OK (1 test)\n", receipt=receipt)
                probe.verify_instrumentation(
                    f"INSTRUMENTATION_STATUS: akula_ui_check={receipt}\nOK (1 test)\n",
                    receipt=receipt, receipt_key="akula_ui_check",
                )

    def test_zero_or_multiple_tests_are_not_success(self):
        for output in ("OK (0 tests)\n", "OK (2 tests)\n", "No tests found\n"):
            with self.subTest(output=output):
                with self.assertRaisesRegex(RuntimeError, "did not pass"):
                    probe.verify_instrumentation(output)

    def test_only_complete_success_line_is_accepted(self):
        for output in ("PREFIX OK (1 test)\n", "OK (1 test) but skipped\n", ""):
            with self.subTest(output=output):
                with self.assertRaises(RuntimeError):
                    probe.verify_instrumentation(output)

    def test_failure_output_must_not_claim_success(self):
        with self.assertRaisesRegex(RuntimeError, "did not pass"):
            probe.verify_instrumentation("FAILURES!!!\nTests run: 1, Failures: 1\n")


if __name__ == "__main__":
    unittest.main()
