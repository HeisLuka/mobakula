package dev.heisluka.mobakula

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S3BindingProbeTest {
    private fun validate(scan: S3BindingProbe.Scan, count: Int) =
        S3BindingProbe.validate(scan, count, count, false, false, false)

    @Test
    fun noJournalMeansAllDocumentsUnmappedNotImported() {
        val scan = S3BindingProbe.Scan(available = true, unmapped = 5)
        assertEquals(scan, validate(scan, 5))
        assertThrows(IOException::class.java) {
            validate(scan.copy(exact = 1), 6)
        }
    }

    @Test
    fun journalIsAdvisoryAndOutstandingIntentIsNotSuccess() {
        val scan = S3BindingProbe.Scan(
            available = true, journalExists = true, exact = 1,
            outdated = 1, prepared = 1, held = 1, unmapped = 1
        )
        assertEquals(scan, validate(scan, 5))
        assertThrows(IOException::class.java) {
            S3BindingProbe.validate(scan, 5, 5, true, false, false)
        }
        assertThrows(IOException::class.java) {
            S3BindingProbe.validate(scan, 5, 5, false, true, false)
        }
        assertThrows(IOException::class.java) {
            S3BindingProbe.validate(scan, 5, 5, false, false, true)
        }
    }

    @Test
    fun uninitializedVaultCannotMasqueradeAsEmptyVerifiedMapping() {
        val scan = S3BindingProbe.Scan()
        assertEquals(scan, S3BindingProbe.validate(
            scan, scanned = 0, candidates = 3,
            ownerVerified = false, importAuthorized = false, synced = false
        ))
        assertThrows(IOException::class.java) {
            validate(scan, 3)
        }
    }

    @Test
    fun invalidOrNegativeCountsFailClosed() {
        val a = S3BindingProbe.Scan(available = true, journalExists = true, prepared = 2)
        assertThrows(IOException::class.java) { validate(a, 3) }
        assertThrows(IOException::class.java) { validate(a.copy(held = -1), 1) }
        assertThrows(IOException::class.java) {
            validate(S3BindingProbe.Scan(unmapped = 2), 2)
        }
    }
}
