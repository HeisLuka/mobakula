package dev.heisluka.mobakula

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Test

/** No Keystore, network, JNI or Android device required. */
class S3LocalSlotProbeTest {
    private fun check(
        scan: S3LocalSlotProbe.Scan,
        examined: Int,
        candidates: Int,
        identityBound: Boolean = false,
        importAuthorized: Boolean = false,
    ): S3LocalSlotProbe.Scan = S3LocalSlotProbe.validate(
        scan, examined, candidates,
        identityBound, importAuthorized, false, false
    )

    @Test
    fun matchedContentsRemainUnboundAndNeverAuthorizeImport() {
        val scan = S3LocalSlotProbe.Scan(
            checked = true, absent = 1,
            identicalButUnbound = 2, conflicting = 1, unsafe = 1,
        )
        assertEquals(scan, check(scan, examined = 5, candidates = 5))
        assertEquals(2, scan.identicalButUnbound)
        assertFalse(scan.identicalButUnbound == scan.absent)
    }

    @Test
    fun forgedCountsAndPrivilegesAreRejected() {
        val valid = S3LocalSlotProbe.Scan(checked = true, absent = 1)
        assertThrows(IOException::class.java) {
            check(valid, examined = 2, candidates = 2)
        }
        assertThrows(IOException::class.java) {
            check(valid, examined = 1, candidates = 1, identityBound = true)
        }
        assertThrows(IOException::class.java) {
            check(valid, examined = 1, candidates = 1, importAuthorized = true)
        }
        assertThrows(IOException::class.java) {
            check(valid.copy(unsafe = -1), examined = 0, candidates = 0)
        }
    }

    @Test
    fun uninitializedVaultMustNotReportZeroAsACompletedScan() {
        val unavailable = S3LocalSlotProbe.Scan()
        assertEquals(unavailable, check(unavailable, examined = 0, candidates = 5))
        assertThrows(IOException::class.java) {
            check(unavailable.copy(absent = 1), examined = 0, candidates = 5)
        }
        assertThrows(IOException::class.java) {
            check(unavailable.copy(checked = true), examined = 0, candidates = 5)
        }
    }
}
