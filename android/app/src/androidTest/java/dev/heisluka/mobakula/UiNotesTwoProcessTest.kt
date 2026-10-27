package dev.heisluka.mobakula

import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real launcher UI against Akula's native File Gate.
 * Creates a test note in the normal vault: run ONLY on a disposable emulator.
 * Script selects each phase separately and force-stops the app between phases.
 */
@RunWith(AndroidJUnit4::class)
class UiNotesTwoProcessTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get() = AtomicFile(File(context.noBackupFilesDir, "m1-ui-proof.json"))
    private val recoveryMarker get() = AtomicFile(File(context.noBackupFilesDir, "m1-recovery-proof.json"))

    private fun requirePhase(expected: String) {
        assumeTrue("Use cold_reopen_probe.py --include-ui",
            InstrumentationRegistry.getArguments().getString("akula_ui_phase") == expected)
        val emulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" ||
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")
        assertTrue("UI proof writes a real note: disposable emulator required", emulator)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitTitle(title: String) {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun report(receipt: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { putString("akula_ui_check", receipt) })
    }

    @Test
    fun prepareNoteThroughUi() {
        requirePhase("prepare")
        marker.delete()
        val title = "UI proof " + UUID.randomUUID().toString().take(8)
        val content = "# " + title + "\nПривет, Akula 🦈\nСтрока 2\n"

        awaitTag("note_create")
        compose.onNodeWithTag("note_create").performClick()
        awaitTag("note_editor")
        compose.onNodeWithTag("note_editor").performTextReplacement(content)
        compose.onNodeWithTag("note_save").assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Файл и история проверены")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("note_back").performClick()
        awaitTitle(title)

        val receipt = JSONObject().put("pid", Process.myPid())
            .put("title", title).put("content", content)
        val atomic = marker
        val stream = atomic.startWrite()
        try {
            stream.write(receipt.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (cause: Exception) {
            atomic.failWrite(stream)
            throw cause
        }
        report("ui_prepared")
    }

    @Test
    fun prepareUnsavedDraftForColdRecovery() {
        requirePhase("recovery_prepare")
        recoveryMarker.delete()
        val title = "Recovery proof " + UUID.randomUUID().toString().take(8)
        val saved = "# " + title + "\nСохранённый текст\n"
        val draft = "# " + title + "\nНезавершённый черновик: 你好, э🙂\n"

        awaitTag("note_create")
        compose.onNodeWithTag("note_create").performClick()
        awaitTag("note_editor")
        compose.onNodeWithTag("note_editor").performTextReplacement(saved)
        compose.onNodeWithTag("note_save").assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Файл и история проверены")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("note_editor").performTextReplacement(draft)
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(
                "Черновик скопирован локально; основной файл ещё не сохранён",
            ).fetchSemanticsNodes().isNotEmpty()
        }
        val state = ViewModelProvider(compose.activity)[NotesViewModel::class.java]
        // Freeze File Gate autosave after the checkpoint is acknowledged;
        // compositional preedit is simulated, not generated by a real IME.
        compose.runOnUiThread { state.update(draft, composing = true) }
        compose.runOnIdle { assertTrue("Expected unsaved draft", state.dirty) }

        val receipt = JSONObject().put("title", title)
            .put("saved", saved).put("draft", draft)
            .put("pid", Process.myPid())
        val stream = recoveryMarker.startWrite()
        try {
            stream.write(receipt.toString().toByteArray(Charsets.UTF_8))
            recoveryMarker.finishWrite(stream)
        } catch (cause: Exception) {
            recoveryMarker.failWrite(stream)
            throw cause
        }
        report("ui_recovery_prepared")
    }

    @Test
    fun recoverDraftInNewProcessWithoutOverwritingFile() {
        requirePhase("recovery_verify")
        val receipt = JSONObject(recoveryMarker.openRead().use {
            it.readBytes().toString(Charsets.UTF_8)
        })
        assertNotEquals(receipt.getInt("pid"), Process.myPid())
        val title = receipt.getString("title")
        awaitTitle(title)
        compose.onNodeWithText(title).performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Найдена резервная копия черновика")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The verified Rust file remains intact until the user deliberately
        // restores the local checkpoint, then chooses to save.
        val state = ViewModelProvider(compose.activity)[NotesViewModel::class.java]
        compose.runOnIdle {
            assertEquals(receipt.getString("saved"), state.opened?.content)
        }
        compose.onNodeWithText("Восстановить").performClick()
        awaitTag("note_editor")
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onNodeWithTag("note_editor").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text == receipt.getString("draft")
        }
        compose.onNodeWithTag("note_save").assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Файл и история проверены")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("note_back").performClick()
        awaitTitle(title)
        recoveryMarker.delete()
        report("ui_recovery_verified")
    }

    @Test
    fun recreateActivityRetainsPendingDraftAndResumesAutoSave() {
        requirePhase("recreate")
        val title = "Recreation proof " + UUID.randomUUID().toString().take(8)
        val expected = "# " + title + "\nЧерновик после пересоздания: 你好, э🙂\n"

        awaitTag("note_create")
        compose.onNodeWithTag("note_create").performClick()
        awaitTag("note_editor")
        // Exercise the actual editor first, then synthesize an in-progress
        // composition state in the ViewModel: Compose's SetText semantics do not
        // generate native IME composing spans. This is NOT a physical IME test.
        compose.onNodeWithTag("note_editor")
            .performTextReplacement("# " + title + "\nНачальный текст")
        val state = ViewModelProvider(compose.activity)[NotesViewModel::class.java]
        compose.runOnUiThread { state.update(expected, composing = true) }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onNodeWithTag("note_editor")
                .fetchSemanticsNode().config[SemanticsProperties.EditableText].text == expected
        }
        compose.runOnIdle {
            assertTrue("Before recreation this must be an unsaved draft", state.dirty)
            assertTrue("Before recreation input is marked composing", state.imeComposing)
        }

        compose.activityRule.scenario.recreate()
        awaitTag("note_editor")
        val actual = compose.onNodeWithTag("note_editor")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("Activity recreation dropped editor contents", expected, actual)

        // LaunchedEffect(state.opened?.id) must release the stale composing
        // state; otherwise the debounce save never starts after recreation.
        compose.waitUntil(timeoutMillis = 15_000) { !state.imeComposing }
        compose.waitUntil(timeoutMillis = 30_000) { !state.dirty && !state.busy }
        compose.onNodeWithTag("note_back").performClick()
        awaitTitle(title)
        report("ui_recreated")
    }

    /**
     * Compose semantics incremental input, not an OS IME composition event.
     * A previously saved note must retain every existing character after the
     * first new typed character, and File Gate must persist the exact result.
     */
    @Test
    fun firstTypedCharacterKeepsPreviouslySavedText() {
        requirePhase("first_input")
        val title = "First input " + UUID.randomUUID().toString().take(8)
        val original = "# " + title + "\nСохранённый текст: 你好, э🙂\n"

        awaitTag("note_create")
        compose.onNodeWithTag("note_create").performClick()
        awaitTag("note_editor")
        compose.onNodeWithTag("note_editor").performTextReplacement(original)
        compose.onNodeWithTag("note_save").assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Файл и история проверены")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("note_back").performClick()
        awaitTitle(title)
        compose.onNodeWithText(title).performClick()
        awaitTag("note_editor")

        fun editable(): String = compose.onNodeWithTag("note_editor")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("Reopening changed the saved text before typing", original, editable())
        compose.onNodeWithTag("note_editor").performClick()
        compose.onNodeWithTag("note_editor").performTextInput("!")
        compose.waitUntil(timeoutMillis = 15_000) {
            val value = editable()
            value.length == original.length + 1 && value.replaceFirst("!", "") == original
        }
        val expected = editable()
        val state = ViewModelProvider(compose.activity)[NotesViewModel::class.java]
        compose.waitUntil(timeoutMillis = 30_000) { !state.dirty && !state.busy }
        compose.onNodeWithTag("note_back").performClick()
        awaitTitle(title)
        compose.onNodeWithText(title).performClick()
        awaitTag("note_editor")
        assertEquals("The first typed character was not saved exactly", expected, editable())
        report("ui_first_input_preserved")
    }

    @Test
    fun reopenNoteThroughUi() {
        requirePhase("verify")
        val atomic = marker
        assertTrue("UI prepare marker missing", atomic.baseFile.isFile)
        val saved = JSONObject(atomic.openRead().use {
            it.readBytes().toString(Charsets.UTF_8)
        })
        assertNotEquals("Android app process did not restart",
            saved.getInt("pid"), Process.myPid())

        val title = saved.getString("title")
        awaitTitle(title)
        compose.onNodeWithText(title).performClick()
        awaitTag("note_editor")
        // OutlinedTextField exposes its label as Text and the note as EditableText.
        // assertTextEquals includes both and incorrectly fails on the label.
        val actual = compose.onNodeWithTag("note_editor")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(saved.getString("content"), actual)
        atomic.delete()
        report("ui_verified")
    }
}
