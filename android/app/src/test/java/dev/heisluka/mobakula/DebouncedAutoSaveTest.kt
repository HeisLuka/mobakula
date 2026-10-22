package dev.heisluka.mobakula

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebouncedAutoSaveTest {
    @Test
    fun savesOnlyAfterFullIdleWindow() = runTest {
        var saves = 0
        val pending = DebouncedAutoSave(this, { saves++ }, idleMillis = 1_200L)
        pending.schedule()
        advanceTimeBy(1_199)
        runCurrent()
        assertEquals(0, saves)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, saves)
    }

    @Test
    fun newTypingResetsDeadline() = runTest {
        var saves = 0
        val pending = DebouncedAutoSave(this, { saves++ }, idleMillis = 1_200L)
        pending.schedule()
        advanceTimeBy(800)
        pending.schedule()
        advanceTimeBy(800)
        runCurrent()
        assertEquals(0, saves)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1, saves)
    }

    @Test
    fun discardOrManualSaveCancelsQueuedWrite() = runTest {
        var saves = 0
        val pending = DebouncedAutoSave(this, { saves++ }, idleMillis = 1_200L)
        pending.schedule()
        pending.cancel()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(0, saves)
    }

    @Test
    fun unknownHistoryOrFailedAttemptNeverAutoRetries() {
        assertFalse(canScheduleAutoSave(true, false, NoteSaveEvidence.NEEDS_REVIEW, false))
        assertFalse(canScheduleAutoSave(true, false, NoteSaveEvidence.VERIFIED, true))
        assertFalse(canScheduleAutoSave(true, true, NoteSaveEvidence.VERIFIED, false))
        assertFalse(canScheduleAutoSave(false, false, NoteSaveEvidence.VERIFIED, false))
        assertTrue(canScheduleAutoSave(true, false, NoteSaveEvidence.FILE_READ, false))
        assertTrue(canScheduleAutoSave(true, false, NoteSaveEvidence.VERIFIED, false))
    }
}
