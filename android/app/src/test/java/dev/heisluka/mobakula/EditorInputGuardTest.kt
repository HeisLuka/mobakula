package dev.heisluka.mobakula

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInputGuardTest {
    @Test
    fun activeEditorCanAcceptFirstInputWithoutBlockingComposition() {
        assertTrue(editorInputAccepted("note-a", 5, "note-a", 5, busy = false))
    }

    @Test
    fun delayedKeyboardEventFromPreviousNoteIsRejected() {
        assertFalse(editorInputAccepted("note-a", 5, "note-b", 6, busy = false))
        assertFalse(editorInputAccepted("note-a", 5, null, 6, busy = false))
    }

    @Test
    fun rapidCloseAndReopenOfSameNoteRetiresOldImeSession() {
        assertFalse(editorInputAccepted("note-a", 5, "note-a", 6, busy = false))
        assertTrue(editorInputAccepted("note-a", 6, "note-a", 6, busy = false))
    }

    @Test
    fun activityRecreationRetiresPreviousComposeCallbacks() {
        assertFalse(editorInputAccepted("note-a", 6, "note-a", 7, busy = false))
        assertTrue(editorInputAccepted("note-a", 7, "note-a", 7, busy = false))
    }

    @Test
    fun savingOrLoadingRefusesDelayedKeyboardEvent() {
        assertFalse(editorInputAccepted("note-a", 5, "note-a", 5, busy = true))
    }
}
