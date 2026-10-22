package dev.heisluka.mobakula

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Bounded, in-memory undo history for one open note.
 *
 * Undo edits the current draft through the existing ViewModel/File Gate path.
 * It is not a replacement for durable Akula HistoryService snapshots.
 */
internal class DraftEditHistory(private val limit: Int = 48) {
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    init {
        require(limit > 0) { "Undo history limit must be positive" }
    }

    fun record(before: TextFieldValue, after: TextFieldValue) {
        if (before.text == after.text) return
        undoStack.addLast(before.copy(composition = null))
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        updateAvailability()
    }

    fun undo(current: TextFieldValue): TextFieldValue? {
        if (undoStack.isEmpty()) return null
        redoStack.addLast(current.copy(composition = null))
        val previous = undoStack.removeLast()
        updateAvailability()
        return previous
    }

    fun redo(current: TextFieldValue): TextFieldValue? {
        if (redoStack.isEmpty()) return null
        undoStack.addLast(current.copy(composition = null))
        if (undoStack.size > limit) undoStack.removeFirst()
        val next = redoStack.removeLast()
        updateAvailability()
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        updateAvailability()
    }

    private fun updateAvailability() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }
}
