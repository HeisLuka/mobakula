package dev.heisluka.mobakula

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownFormattingTest {
    private fun edit(
        text: String,
        start: Int,
        end: Int = start,
        command: MarkdownCommand,
    ): TextFieldValue = applyMarkdownCommand(
        TextFieldValue(text, selection = TextRange(start, end)),
        command,
    )

    @Test
    fun boldWrapsSelectedRussianTextAndPreservesSelection() {
        val result = edit("Привет мир", 0, 6, MarkdownCommand.BOLD)
        assertEquals("**Привет** мир", result.text)
        assertEquals(TextRange(2, 8), result.selection)
    }

    @Test
    fun boldRemovesExistingSurroundingMarkers() {
        val result = edit("**текст**", 2, 7, MarkdownCommand.BOLD)
        assertEquals("текст", result.text)
        assertEquals(TextRange(0, 5), result.selection)
    }

    @Test
    fun italicHandlesReversedSelection() {
        val result = edit("мир", 3, 0, MarkdownCommand.ITALIC)
        assertEquals("*мир*", result.text)
        assertEquals(TextRange(4, 1), result.selection)
    }

    @Test
    fun boldWithoutSelectionPlacesCaretBetweenMarkers() {
        val result = edit("Привет", 6, command = MarkdownCommand.BOLD)
        assertEquals("Привет****", result.text)
        assertEquals(TextRange(8), result.selection)
    }

    @Test
    fun headingFormatsCurrentLineOnlyAndCanToggleOff() {
        val result = edit("Первая\nВторая", 9, command = MarkdownCommand.HEADING)
        assertEquals("Первая\n# Вторая", result.text)
        assertEquals(TextRange(11), result.selection)
        val again = applyMarkdownCommand(result, MarkdownCommand.HEADING)
        assertEquals("Первая\nВторая", again.text)
        assertEquals(TextRange(9), again.selection)
    }

    @Test
    fun headingConvertsOtherHeadingLevelToH1() {
        val result = edit("### Тема", 6, command = MarkdownCommand.HEADING)
        assertEquals("# Тема", result.text)
    }

    @Test
    fun bulletsHandleMultipleSelectedLinesButNotTrailingLine() {
        val source = "первый\nвторой\nтретий"
        val result = edit(source, 0, 14, MarkdownCommand.BULLET)
        assertEquals("- первый\n- второй\nтретий", result.text)
        assertEquals(TextRange(2, 18), result.selection)
    }

    @Test
    fun bulletsCanToggleAllSelectedLinesOff() {
        val source = "- один\n- два"
        val result = edit(source, 0, source.length, MarkdownCommand.BULLET)
        assertEquals("один\nдва", result.text)
    }

    @Test
    fun checkboxConvertsBulletAndPreservesNewlines() {
        val source = "- задача\r\nещё"
        val result = edit(source, 4, command = MarkdownCommand.CHECKBOX)
        assertEquals("- [ ] задача\r\nещё", result.text)
        assertEquals(TextRange(8), result.selection)
    }

    @Test
    fun checkboxCanRemoveCompletedCheckbox() {
        val source = "- [x] готово"
        val result = edit(source, 8, command = MarkdownCommand.CHECKBOX)
        assertEquals("готово", result.text)
        assertEquals(TextRange(2), result.selection)
    }

    @Test
    fun linkWithSelectionSelectsUrlForReplacement() {
        val result = edit("Справка", 0, 7, MarkdownCommand.LINK)
        assertEquals("[Справка](https://)", result.text)
        assertEquals("https://", result.text.substring(result.selection.min, result.selection.max))
    }

    @Test
    fun linkWithoutSelectionSelectsLabel() {
        val result = edit("Текст", 5, command = MarkdownCommand.LINK)
        assertEquals("Текст[текст](https://)", result.text)
        assertEquals("текст", result.text.substring(result.selection.min, result.selection.max))
    }

    @Test
    fun linkDoesNotCorruptMultilineSelection() {
        val input = TextFieldValue("два\nабзаца", selection = TextRange(0, 10))
        assertEquals(input, applyMarkdownCommand(input, MarkdownCommand.LINK))
    }

    @Test
    fun emojiSelectionKeepsUtf16Offsets() {
        val source = "🦈 язык"
        val result = edit(source, 0, 2, MarkdownCommand.BOLD)
        assertEquals("**🦈** язык", result.text)
        assertEquals(TextRange(2, 4), result.selection)
    }
}
