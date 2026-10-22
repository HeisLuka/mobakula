package dev.heisluka.mobakula

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Pure UTF-16 text edits for the Compose Markdown toolbar (no second note writer). */
internal enum class MarkdownCommand(val glyph: String, val description: String) {
    BOLD("Ж", "Жирный"),
    ITALIC("К", "Курсив"),
    HEADING("H1", "Заголовок"),
    BULLET("•", "Маркированный список"),
    CHECKBOX("☐", "Чекбокс"),
    TASK_DONE("✓", "Отметить выполнение задачи"),
    LINK("↗", "Ссылка"),
}

internal fun applyMarkdownCommand(
    editor: TextFieldValue,
    command: MarkdownCommand,
): TextFieldValue = when (command) {
    MarkdownCommand.BOLD -> wrapSelection(editor, "**")
    MarkdownCommand.ITALIC -> wrapSelection(editor, "*")
    MarkdownCommand.HEADING, MarkdownCommand.BULLET, MarkdownCommand.CHECKBOX ->
        editLinePrefixes(editor, command)
    MarkdownCommand.LINK -> insertLink(editor)
    MarkdownCommand.TASK_DONE -> toggleTaskCompletion(editor)
}

private fun wrapSelection(editor: TextFieldValue, marker: String): TextFieldValue {
    val text = editor.text
    val from = editor.selection.min.coerceIn(0, text.length)
    val to = editor.selection.max.coerceIn(from, text.length)
    if (from == to) {
        val updated = text.replaceRange(from, to, marker + marker)
        return editor.copy(
            text = updated, selection = TextRange(from + marker.length), composition = null,
        )
    }
    val wrapped = from >= marker.length &&
        text.substring(from - marker.length, from) == marker &&
        text.substring(to, (to + marker.length).coerceAtMost(text.length)) == marker
    return if (wrapped) {
        val updated = text.removeRange(to, to + marker.length)
            .removeRange(from - marker.length, from)
        editor.copy(
            text = updated,
            selection = orderedSelection(editor.selection, from - marker.length, to - marker.length),
            composition = null,
        )
    } else {
        val updated = text.replaceRange(from, to, marker + text.substring(from, to) + marker)
        editor.copy(
            text = updated,
            selection = orderedSelection(editor.selection, from + marker.length, to + marker.length),
            composition = null,
        )
    }
}

private fun insertLink(editor: TextFieldValue): TextFieldValue {
    val text = editor.text
    val from = editor.selection.min.coerceIn(0, text.length)
    val to = editor.selection.max.coerceIn(from, text.length)
    val selected = text.substring(from, to)
    // A multiline link label is not a valid edit for this minimal toolbar.
    if ('\n' in selected || '\r' in selected) return editor
    val label = selected.ifEmpty { "текст" }
    val suffix = "https://"
    val updated = text.replaceRange(from, to, "[$label]($suffix)")
    val selection = if (from == to) {
        TextRange(from + 1, from + 1 + label.length)
    } else {
        val urlStart = from + label.length + 3
        TextRange(urlStart, urlStart + suffix.length)
    }
    return editor.copy(text = updated, selection = selection, composition = null)
}

private data class SelectedLine(val offset: Int, val content: String)
private data class PrefixEdit(val offset: Int, val removed: Int, val inserted: String)

private fun selectedLines(text: String, selection: TextRange): List<SelectedLine> {
    val from = selection.min.coerceIn(0, text.length)
    val to = selection.max.coerceIn(from, text.length)
    val last = if (to > from && text[to - 1] == '\n') to - 1 else to
    // A caret on the first empty line must not skip that line.
    var start = if (from == 0) 0 else text.lastIndexOf('\n', from - 1) + 1
    val lines = mutableListOf<SelectedLine>()
    while (true) {
        val newline = text.indexOf('\n', start)
        val end = if (newline < 0) text.length else newline
        lines.add(SelectedLine(start, text.substring(start, end)))
        if (end >= last || newline < 0) break
        start = newline + 1
    }
    return lines
}

private val headingPrefix = Regex("^#{1,6} ")

private fun taskPrefix(line: String): String? = when {
    line.startsWith("- [ ] ") -> "- [ ] "
    line.startsWith("- [x] ") -> "- [x] "
    line.startsWith("- [X] ") -> "- [X] "
    else -> null
}

private fun toggleTaskCompletion(editor: TextFieldValue): TextFieldValue {
    val tasks = selectedLines(editor.text, editor.selection)
        .filter { taskPrefix(it.content) != null }
    if (tasks.isEmpty()) return editor
    val allDone = tasks.all {
        it.content.startsWith("- [x] ") || it.content.startsWith("- [X] ")
    }
    val next = if (allDone) "- [ ] " else "- [x] "
    var updated = editor.text
    // All task markers have the same UTF-16 length; caret offsets stay valid.
    tasks.asReversed().forEach { line ->
        updated = updated.replaceRange(line.offset, line.offset + 6, next)
    }
    return editor.copy(text = updated, composition = null)
}

private fun editLinePrefixes(editor: TextFieldValue, command: MarkdownCommand): TextFieldValue {
    val lines = selectedLines(editor.text, editor.selection)
    val allActive = lines.all { line ->
        when (command) {
            MarkdownCommand.HEADING -> line.content.startsWith("# ")
            MarkdownCommand.BULLET -> line.content.startsWith("- ") &&
                taskPrefix(line.content) == null
            MarkdownCommand.CHECKBOX -> taskPrefix(line.content) != null
            else -> false
        }
    }
    val edits = lines.map { line ->
        val oldHeading = headingPrefix.find(line.content)?.value.orEmpty()
        val currentTask = taskPrefix(line.content)
        when (command) {
            MarkdownCommand.HEADING -> PrefixEdit(
                line.offset,
                oldHeading.length,
                if (allActive) "" else "# ",
            )
            MarkdownCommand.BULLET -> PrefixEdit(
                line.offset,
                if (currentTask != null) currentTask.length
                    else if (line.content.startsWith("- ")) 2 else 0,
                if (allActive) "" else "- ",
            )
            MarkdownCommand.CHECKBOX -> PrefixEdit(
                line.offset,
                currentTask?.length ?: if (line.content.startsWith("- ")) 2 else 0,
                if (allActive) "" else "- [ ] ",
            )
            else -> error("Unexpected command")
        }
    }
    var text = editor.text
    var anchor = editor.selection.start
    var cursor = editor.selection.end
    for (edit in edits.asReversed()) {
        text = text.replaceRange(edit.offset, edit.offset + edit.removed, edit.inserted)
        anchor = mapOffset(anchor, edit)
        cursor = mapOffset(cursor, edit)
    }
    return editor.copy(
        text = text,
        selection = TextRange(anchor, cursor),
        composition = null,
    )
}

private fun mapOffset(offset: Int, edit: PrefixEdit): Int = when {
    offset < edit.offset -> offset
    offset <= edit.offset + edit.removed -> edit.offset + edit.inserted.length
    else -> offset + edit.inserted.length - edit.removed
}

private fun orderedSelection(original: TextRange, from: Int, to: Int): TextRange =
    if (original.start > original.end) TextRange(to, from) else TextRange(from, to)
