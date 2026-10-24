"""Guard the S3 status UI against aggregate-only or source-mismatched receipts."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JNI = ROOT / "rust/mobile-jni/src/s3_binding_session.rs"
KOTLIN = ROOT / "android/app/src/main/java/dev/heisluka/mobakula/S3BindingProbe.kt"
READ = ROOT / "android/app/src/main/java/dev/heisluka/mobakula/S3ReadOnlyProbe.kt"
UI = ROOT / "android/app/src/main/java/dev/heisluka/mobakula/S3CandidateCards.kt"


class S3DocumentStatesContract(unittest.TestCase):
    def test_native_receipt_exposes_states_without_local_file_identity(self):
        code = JNI.read_text(encoding="utf8")
        self.assertIn('"documentStates":states', code)
        self.assertIn('"documentStates":[]', code)
        self.assertIn('"documentId": candidate.document_id.to_string()', code)
        self.assertIn('"state": state', code)
        self.assertNotIn('"localNoteId":', code)
        self.assertNotIn('"remoteOwnerVerified":true', code)

    def test_ui_filters_always_require_exact_verified_document_states(self):
        mapping = KOTLIN.read_text(encoding="utf8")
        read = READ.read_text(encoding="utf8")
        ui = UI.read_text(encoding="utf8")
        self.assertIn('getJSONArray("documentStates")', mapping)
        self.assertIn('result.states.size != scanned', mapping)
        self.assertIn('numbers != classified', mapping)
        self.assertIn('bindingScan.states.keys !=', read)
        self.assertIn('canOfferCandidate(candidate)', ui)
        self.assertIn('S3BindingProbe.State.UNMAPPED', ui)
        self.assertIn('Поиск по идентификатору', ui)
        runner = (ROOT / "scripts/android/cold_reopen_probe.py").read_text("utf8")
        device = (ROOT / "android/app/src/androidTest/java/dev/heisluka/mobakula/"
                  "S3CandidateCardsUiTest.kt").read_text("utf8")
        self.assertIn("S3CandidateCardsUiTest#newDocumentsOnlyCanBeImported", runner)
        self.assertIn("fun newDocumentsOnlyCanBeImported()", device)



if __name__ == "__main__":
    unittest.main()
