package dev.heisluka.mobakula

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class S3ManualImportTest {
    private val doc = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val revision = "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val hash = "a".repeat(64)
    private fun candidate() = S3ManualImport.Candidate(doc, revision, hash, 5)

    @Test fun replayRejectionIsDistinctFromNetworkOrCorruption() {
        assertTrue(S3ManualImport.classifyImportFailure("existing_binding_needs_review")
            is S3ManualImport.ExistingSourceBinding)
        for (code in listOf("s3_timeout", "http_403", "invalid_session",
            "payload_does_not_match_verified_revision", "receive_needs_review")) {
            assertTrue(S3ManualImport.classifyImportFailure(code)
                !is S3ManualImport.ExistingSourceBinding)
        }
    }

    @Test fun acceptsOnlyExplicitImmutableTypedCandidates() {
        assertEquals(doc, S3ManualImport.validateCandidates(
            listOf(candidate()), 1).single().documentId)
        assertThrows(IOException::class.java) {
            S3ManualImport.validateCandidates(listOf(candidate()), 2)
        }
        assertThrows(IOException::class.java) {
            S3ManualImport.validateCandidates(listOf(candidate(), candidate()), 2)
        }
    }

    @Test fun rejectsOversizedAndTraversalCandidates() {
        assertThrows(IOException::class.java) {
            S3ManualImport.validateCandidates(
                listOf(candidate().copy(documentId = "../other")), 1)
        }
        assertThrows(IOException::class.java) {
            S3ManualImport.validateCandidates(
                listOf(candidate().copy(bytes = 1024 * 1024 + 1)), 1)
        }
        assertThrows(IOException::class.java) {
            S3ManualImport.validateCandidates(
                listOf(candidate().copy(blobHash = "BAD-HASH")), 1)
        }
    }
}
