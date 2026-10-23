package dev.heisluka.mobakula

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Keystore + AtomicFile on a disposable Android emulator, never the user's profile. */
@RunWith(AndroidJUnit4::class)
class S3ProtectedInviteStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = "instrumentation-s3-fixture"
    private fun store() = S3ProtectedInviteStore(context, scope)
    private fun sealedFile() =
        File(context.noBackupFilesDir, "s3-profiles/$scope.sealed")

    @Test
    fun keystoreEnvelopeSurvivesStoreRecreationWithNoPlaintextAtRest() {
        store().delete()
        try {
            val invitation = "ENCRYPTED-INVITE-FIXTURE-1234".toByteArray()
            val originalPassword = "шифрованный пароль для телефона".toCharArray()
            store().saveNew(invitation, originalPassword)
            originalPassword.fill('\u0000')
            assertTrue(store().exists())
            val onDisk = sealedFile().readBytes()
            val ascii = String(onDisk, Charsets.ISO_8859_1)
            assertFalse(ascii.contains("ENCRYPTED-INVITE-FIXTURE"))
            assertFalse(ascii.contains("шифрованный"))
            val freshStore = store()
            freshStore.open().use { restored ->
                assertArrayEquals(invitation, restored.encryptedInvitation)
                assertEquals("шифрованный пароль для телефона", String(restored.password))
            }
            assertThrows(IOException::class.java) {
                freshStore.saveNew(invitation, "a new different password long enough".toCharArray())
            }
        } finally {
            store().delete()
        }
        assertFalse(store().exists())
    }

    @Test
    fun tamperedCiphertextIsRejectedWithoutReturningAnyFields() {
        store().delete()
        try {
            store().saveNew("encrypted-payload".toByteArray(),
                "a-test-password-long-enough".toCharArray())
            val file = sealedFile()
            val changed = file.readBytes()
            changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
            file.writeBytes(changed)
            assertThrows(IOException::class.java) { store().open().close() }
        } finally {
            store().delete()
        }
    }

    @Test
    fun foreignScopeAndInvalidSizesDoNotUnlockProfile() {
        store().delete()
        val foreign = S3ProtectedInviteStore(context, "other-test-s3-fixture")
        foreign.delete()
        try {
            assertThrows(IOException::class.java) {
                store().saveNew(ByteArray(16 * 1024 + 1), "valid-long-password".toCharArray())
            }
            assertFalse(store().exists())
            store().saveNew(byteArrayOf(1, 2, 3),
                "a-second-password-long-enough".toCharArray())
            assertThrows(IOException::class.java) { foreign.open().close() }
        } finally {
            store().delete()
            foreign.delete()
        }
    }
}
