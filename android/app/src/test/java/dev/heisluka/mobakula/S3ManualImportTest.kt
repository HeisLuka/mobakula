package dev.heisluka.mobakula

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S3ManualImportTest {
    private val doc = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val revision = "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val hash = "a".repeat(64)
    private fun candidate() = S3ManualImport.Candidate(doc, revision, hash, 5)

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
