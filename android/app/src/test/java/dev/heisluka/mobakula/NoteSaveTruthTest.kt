package dev.heisluka.mobakula

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSaveTruthTest {
    @Test
    fun recoveredPartialSaveIsNotLabelledAsSaved() {
        val status = noteSaveStatus(false, NoteSaveEvidence.NEEDS_REVIEW)
        assertTrue(status.contains("история не подтверждена"))
        assertFalse(status.contains("Сохранено"))
        assertFalse(canSaveNote(true, NoteSaveEvidence.NEEDS_REVIEW))
    }

    @Test
    fun diskReadAloneDoesNotClaimHistoryVerification() {
        assertEquals("Файл прочитан; история не проверена",
            noteSaveStatus(false, NoteSaveEvidence.FILE_READ))
        assertTrue(canSaveNote(true, NoteSaveEvidence.FILE_READ))
    }

    @Test
    fun onlyVerifiedCleanStateClaimsFileAndHistory() {
        assertEquals("Файл и история проверены",
            noteSaveStatus(false, NoteSaveEvidence.VERIFIED))
        assertEquals("Есть несохранённые изменения",
            noteSaveStatus(true, NoteSaveEvidence.VERIFIED))
        assertFalse(canSaveNote(false, NoteSaveEvidence.VERIFIED))
        assertTrue(canSaveNote(true, NoteSaveEvidence.VERIFIED))
    }

    @Test
    fun editingAfterAnUncertainWriteKeepsWarning() {
        val status = noteSaveStatus(true, NoteSaveEvidence.NEEDS_REVIEW)
        assertTrue(status.contains("требует проверки"))
        assertFalse(status.contains("Сохранено"))
    }
}
