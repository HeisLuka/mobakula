package dev.heisluka.mobakula

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftEditHistoryTest {
    private fun field(text: String, caret: Int = text.length) =
        TextFieldValue(text, selection = TextRange(caret))

    @Test
    fun undoThenRedoRestoresTextAndCaret() {
        val history = DraftEditHistory()
        val old = field("Заметка", 2)
        val modified = field("Заметка!", 8)
        history.record(old, modified)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        val undo = history.undo(modified)!!
        assertEquals(old, undo)
        assertEquals(TextRange(2), undo.selection)
        assertTrue(history.canRedo)
        val redo = history.redo(undo)!!
        assertEquals(modified, redo)
        assertFalse(history.canRedo)
    }

    @Test
    fun selectionChangeDoesNotCreateUndoStep() {
        val history = DraftEditHistory()
        history.record(field("abc", 0), field("abc", 3))
        assertFalse(history.canUndo)
        assertNull(history.undo(field("abc")))
    }

    @Test
    fun newEditAfterUndoClearsRedoBranch() {
        val history = DraftEditHistory()
        history.record(field("a"), field("ab"))
        val undone = history.undo(field("ab"))!!
        history.record(undone, field("ac"))
        assertFalse(history.canRedo)
        assertEquals("a", history.undo(field("ac"))!!.text)
    }

    @Test
    fun historyIsBoundedAndOldestEditsAreDropped() {
        val history = DraftEditHistory(limit = 2)
        history.record(field("0"), field("1"))
        history.record(field("1"), field("2"))
        history.record(field("2"), field("3"))
        val step1 = history.undo(field("3"))!!
        val step2 = history.undo(step1)!!
        assertEquals("2", step1.text)
        assertEquals("1", step2.text)
        assertNull(history.undo(step2))
    }

    @Test
    fun clearAfterExternalReloadForbidsOldUndo() {
        val history = DraftEditHistory()
        history.record(field("old"), field("dirty"))
        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun formattingRoundTripIsTrackedWithoutWritingFile() {
        val history = DraftEditHistory()
        val before = TextFieldValue("рыба", selection = TextRange(0, 4))
        val after = applyMarkdownCommand(before, MarkdownCommand.BOLD)
        history.record(before, after)
        assertEquals("**рыба**", after.text)
        assertEquals(before, history.undo(after))
    }
}
