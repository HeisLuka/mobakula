package dev.heisluka.mobakula

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S3BindingProbeTest {
    private fun states(vararg state: S3BindingProbe.State): Map<String, S3BindingProbe.State> =
        state.mapIndexed { i, value ->
            "00000000-0000-4000-8000-" + (i + 1).toString().padStart(12, '0') to value
        }.toMap()

    private fun validate(scan: S3BindingProbe.Scan, count: Int) =
        S3BindingProbe.validate(scan, count, count, false, false, false)

    @Test
    fun noJournalMeansAllDocumentsUnmappedNotImported() {
        val scan = S3BindingProbe.Scan(
            available = true, unmapped = 5,
            states = states(
                S3BindingProbe.State.UNMAPPED, S3BindingProbe.State.UNMAPPED,
                S3BindingProbe.State.UNMAPPED, S3BindingProbe.State.UNMAPPED,
                S3BindingProbe.State.UNMAPPED)
        )
        assertEquals(scan, validate(scan, 5))
        assertThrows(IOException::class.java) {
            validate(scan.copy(exact = 1), 6)
        }
    }

    @Test
    fun journalIsAdvisoryAndOutstandingIntentIsNotSuccess() {
        val scan = S3BindingProbe.Scan(
            available = true, journalExists = true, exact = 1,
            outdated = 1, prepared = 1, held = 1, unmapped = 1,
            states = states(
                S3BindingProbe.State.EXACT, S3BindingProbe.State.OUTDATED,
                S3BindingProbe.State.PREPARED, S3BindingProbe.State.HELD,
                S3BindingProbe.State.UNMAPPED)
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
    fun classificationCountsMustMatchEachIndividualDocument() {
        val original = S3BindingProbe.Scan(
            available = true, journalExists = true, exact = 1, unmapped = 1,
            states = states(S3BindingProbe.State.EXACT, S3BindingProbe.State.UNMAPPED)
        )
        assertEquals(original, validate(original, 2))
        assertThrows(IOException::class.java) {
            validate(original.copy(states =
                states(S3BindingProbe.State.UNMAPPED, S3BindingProbe.State.UNMAPPED)), 2)
        }
        assertThrows(IOException::class.java) {
            validate(original.copy(states = emptyMap()), 2)
        }
        assertThrows(IOException::class.java) {
            S3BindingProbe.State.parse("synced")
        }
        assertThrows(IOException::class.java) {
            S3BindingProbe.State.parse("trusted")
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
