package dev.heisluka.mobakula

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInputGuardTest {
    @Test
    fun activeEditorCanAcceptFirstInputWithoutBlockingComposition() {
        assertTrue(editorInputAccepted("note-a", "note-a", busy = false))
    }

    @Test
    fun delayedKeyboardEventFromPreviousNoteIsRejected() {
        assertFalse(editorInputAccepted("note-a", "note-b", busy = false))
        assertFalse(editorInputAccepted("note-a", null, busy = false))
    }

    @Test
    fun savingOrLoadingRefusesDelayedKeyboardEvent() {
        assertFalse(editorInputAccepted("note-a", "note-a", busy = true))
    }
}
