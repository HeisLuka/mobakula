package dev.heisluka.mobakula

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Negative real-JNI checks: no credentials, no network and no note changes. */
@RunWith(AndroidJUnit4::class)
class S3JniFailClosedDeviceTest {
    @Test
    fun unverifiedSessionsCannotReadBindingsOrCreateNotes() {
        val invitation = JSONObject(
            NativeBridge.beginS3ReadProbe(
                byteArrayOf(1, 2, 3), "invalid-s3-invitation-password".toCharArray()
            )
        )
        assertFalse(invitation.toString(), invitation.getBoolean("ok"))

        val invalidToken = "invalid-probe-session"
        val head = byteArrayOf(1)
        val deniedJournal = JSONObject(
            NativeBridge.inspectS3BindingJournalProbe(invalidToken, head)
        )
        assertFalse(deniedJournal.toString(), deniedJournal.getBoolean("ok"))

        val deniedImport = JSONObject(
            NativeBridge.importS3VerifiedMarkdown(
                invalidToken, head, "11111111-1111-4111-8111-111111111111",
                "# should not be written\n".toByteArray(Charsets.UTF_8),
            )
        )
        assertFalse(deniedImport.toString(), deniedImport.getBoolean("ok"))
        assertFalse(deniedImport.has("localNoteId"))
        assertFalse(deniedImport.has("synced"))
        assertTrue(
            deniedImport.getString("code") in listOf("no_session", "wrong_session"),
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0, Bundle().apply { putString("akula_s3_check", "jni_guarded") },
        )
    }
}
