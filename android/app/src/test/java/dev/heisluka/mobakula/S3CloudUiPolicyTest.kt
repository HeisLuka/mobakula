package dev.heisluka.mobakula

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM checks: UI buttons never authorize a File Gate write themselves. */
class S3CloudUiPolicyTest {
    private val candidate = S3ManualImport.Candidate(
        documentId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
        revisionId = "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee",
        blobHash = "a".repeat(64), bytes = 1024,
    )

    private fun verified() = S3ReadOnlyProbe.Report(
        latestGeneration = 1, manifestsChecked = 1, latestRevisions = 1,
        checkpointRelation = "unchanged",
        contentPreviewSupported = true,
        localSlotsChecked = true,
        bindingScan = S3BindingProbe.Scan(
            available = true, unmapped = 1,
            states = mapOf(candidate.documentId to S3BindingProbe.State.UNMAPPED),
        ),
        manualImportCandidates = listOf(candidate),
    )

    @Test fun importRequiresAllFourVerifiedGatesAndNewCandidate() {
        assertTrue(verified().canOfferManualImport())
        assertFalse(verified().copy(checkpointRelation = "first").canOfferManualImport())
        assertFalse(verified().copy(checkpointRelation = "advanced").canOfferManualImport())
        assertFalse(verified().copy(contentPreviewSupported = false).canOfferManualImport())
        assertFalse(verified().copy(localSlotsChecked = false).canOfferManualImport())
        assertFalse(verified().copy(
            bindingScan = S3BindingProbe.Scan(available = false)
        ).canOfferManualImport())
        assertFalse(verified().copy(
            bindingScan = S3BindingProbe.Scan(available = true, exact = 1)
        ).canOfferManualImport())
        assertFalse(verified().copy(manualImportCandidates = emptyList())
            .canOfferManualImport())
        assertFalse(verified().copy(importedLocalNoteId =
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").canOfferManualImport())
    }

    @Test fun onlyUnmappedDocumentCanBeOfferedForImport() {
        val previouslyReceived = candidate.copy(
            documentId = "cccccccc-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        )
        val report = verified().copy(
            manualImportCandidates = listOf(candidate, previouslyReceived),
            bindingScan = S3BindingProbe.Scan(
                available = true, journalExists = true, unmapped = 1, exact = 1,
                states = mapOf(
                    candidate.documentId to S3BindingProbe.State.UNMAPPED,
                    previouslyReceived.documentId to S3BindingProbe.State.EXACT,
                ),
            ),
        )
        assertTrue(report.canOfferCandidate(candidate))
        assertFalse(report.canOfferCandidate(previouslyReceived))
        for (state in listOf(S3BindingProbe.State.OUTDATED,
                S3BindingProbe.State.PREPARED, S3BindingProbe.State.HELD)) {
            assertFalse(report.copy(bindingScan = report.bindingScan.copy(
                states = report.bindingScan.states +
                    (candidate.documentId to state))).canOfferCandidate(candidate))
        }
        assertEquals("Уже получена", S3BindingProbe.State.EXACT.displayStatus())
        assertEquals("На проверке / HOLD", S3BindingProbe.State.HELD.displayStatus())
    }

    @Test fun pinIsOnlyOfferedForCompleteFirstOrAdvancedHistory() {
        val proposal = S3CheckpointProbe.Assessment(
            relation = "first", candidate = "probe".toByteArray(), expectedPrior = null,
        )
        val base = verified().copy(
            checkpointRelation = "first", checkpointProposal = proposal,
        )
        assertTrue(base.canOfferCheckpointPin())
        assertTrue(base.copy(checkpointRelation = "advanced").canOfferCheckpointPin())
        assertFalse(base.copy(checkpointRelation = "unchanged").canOfferCheckpointPin())
        assertFalse(base.copy(checkpointProposal = null).canOfferCheckpointPin())
        assertFalse(base.copy(contentPreviewSupported = false).canOfferCheckpointPin())
    }

    @Test fun readableSizesRemainBoundedAndNeutral() {
        assertEquals("0 Б", readableS3Bytes(0))
        assertEquals("1023 Б", readableS3Bytes(1023))
        assertEquals("1 КиБ", readableS3Bytes(1024))
        assertEquals("2 КиБ", readableS3Bytes(1025))
        assertEquals("1 МиБ", readableS3Bytes(1024L * 1024))
        assertEquals("—", readableS3Bytes(-1))
    }
}
