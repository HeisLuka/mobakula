package dev.heisluka.mobakula

import android.os.Process
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two independent instrumentation runs, separated by an app force-stop.
 * Never uses the real notes root: this probe owns a private no-backup fixture.
 * Ordinary connectedDebugAndroidTest runs skip these opt-in stages.
 */
@RunWith(AndroidJUnit4::class)
class ColdReopenTwoProcessTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureRoot get() = File(context.noBackupFilesDir, "m1-cold-probe-vault")
    private val marker get() = AtomicFile(File(context.noBackupFilesDir, "m1-cold-probe.json"))

    private fun assumePhase(expected: String) {
        val actual = InstrumentationRegistry.getArguments().getString("akula_cold_phase")
        assumeTrue("Use scripts/android/cold_reopen_probe.py", actual == expected)
    }

    private fun call(input: JSONObject): JSONObject =
        JSONObject(NativeBridge.request(input.toString()))

    private fun requireOk(input: JSONObject): JSONObject {
        val output = call(input)
        assertTrue(output.toString(), output.getBoolean("ok"))
        return output.getJSONObject("data")
    }

    @Test
    fun prepareVerifiedFileBeforeColdStop() {
        assumePhase("prepare")
        marker.delete()
        requireOk(JSONObject().put("action", "init").put("root", fixtureRoot.absolutePath))
        val initial = requireOk(JSONObject().put("action", "create")
            .put("content", "# Cold test\r\nСтрока 🦈"))
        val text = "# Cold test " + UUID.randomUUID() + "\nСтрока 🦈\n"
        val saved = requireOk(JSONObject().put("action", "save")
            .put("id", initial.getString("id"))
            .put("expected_hash", initial.getString("hash"))
            .put("content", text))
        assertEquals(text, saved.getString("content"))
        val receipt = JSONObject()
            .put("pid", Process.myPid())
            .put("id", saved.getString("id"))
            .put("hash", saved.getString("hash"))
            .put("stale_hash", initial.getString("hash"))
            .put("content", text)
        val stream = marker.startWrite()
        try {
            stream.write(receipt.toString().toByteArray(Charsets.UTF_8))
            marker.finishWrite(stream)
        } catch (cause: Exception) {
            marker.failWrite(stream)
            throw cause
        }
        assertTrue(marker.baseFile.isFile)
    }

    @Test
    fun verifySavedFileFromGenuinelyNewAndroidProcess() {
        assumePhase("verify")
        assertTrue("Missing prepare marker", marker.baseFile.isFile)
        val expected = JSONObject(marker.openRead().use {
            it.readBytes().toString(Charsets.UTF_8)
        })
        assertNotEquals("No process restart occurred", expected.getInt("pid"), Process.myPid())
        requireOk(JSONObject().put("action", "init").put("root", fixtureRoot.absolutePath))
        val id = expected.getString("id")
        val actual = requireOk(JSONObject().put("action", "read").put("id", id))
        assertEquals(expected.getString("content"), actual.getString("content"))
        assertEquals(expected.getString("hash"), actual.getString("hash"))
        val listWire = call(JSONObject().put("action", "list"))
        assertTrue(listWire.toString(), listWire.getBoolean("ok"))
        val items = listWire.getJSONArray("data")
        assertTrue((0 until items.length()).any { items.getJSONObject(it).getString("id") == id })
        val stale = call(JSONObject().put("action", "save").put("id", id)
            .put("expected_hash", expected.getString("stale_hash"))
            .put("content", "must not overwrite"))
        assertFalse(stale.getBoolean("ok"))
        assertEquals("conflict", stale.getString("code"))
        marker.delete()
    }
}
