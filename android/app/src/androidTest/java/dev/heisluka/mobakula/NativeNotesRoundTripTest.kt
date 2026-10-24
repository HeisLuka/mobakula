package dev.heisluka.mobakula

import android.Manifest
import android.content.pm.PackageManager
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android runtime: S3 HTTPS permission, JNI, File Gate and HistoryService. */
@RunWith(AndroidJUnit4::class)
class NativeNotesRoundTripTest {
    private fun call(input: JSONObject): JSONObject =
        JSONObject(NativeBridge.request(input.toString()))

    private fun requireOk(input: JSONObject): JSONObject {
        val response = call(input)
        assertTrue(response.optString("message"), response.getBoolean("ok"))
        return response.getJSONObject("data")
    }

    @Test
    fun roundTrip_andS3NetworkPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.INTERNET),
        )
        val smokeRoot = File(context.noBackupFilesDir, "m1-native-smoke-vault")
        requireOk(JSONObject().put("action", "init").put("root", smokeRoot.absolutePath))
        val initial = requireOk(
            JSONObject().put("action", "create").put("content", "# Проверка\nТекст 🦈"),
        )
        val id = initial.getString("id")
        val hash = initial.getString("hash")
        val saved = requireOk(
            JSONObject().put("action", "save").put("id", id)
                .put("expected_hash", hash).put("content", "# Проверка\nБез интернета"),
        )
        val reread = requireOk(JSONObject().put("action", "read").put("id", id))
        assertEquals(saved.getString("content"), reread.getString("content"))
        assertTrue(saved.getString("hash") != hash)
        val listed = call(JSONObject().put("action", "list")).getJSONArray("data")
        assertTrue((0 until listed.length()).any { listed.getJSONObject(it).getString("id") == id })
        val stale = call(
            JSONObject().put("action", "save").put("id", id)
                .put("expected_hash", hash).put("content", "stale"),
        )
        assertFalse(stale.getBoolean("ok"))
        assertEquals("conflict", stale.getString("code"))
    }
}
