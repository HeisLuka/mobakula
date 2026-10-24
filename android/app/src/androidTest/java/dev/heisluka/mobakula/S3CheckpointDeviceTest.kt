package dev.heisluka.mobakula

import android.os.Bundle
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests the real AndroidKeystore + AtomicFile implementation in an isolated
 * noBackupFilesDir scope. No remote S3 account or ordinary notes are touched.
 * A valid local TOFU checkpoint is NOT publisher identity or sync permission.
 */
@RunWith(AndroidJUnit4::class)
class S3CheckpointDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isolated(block: (S3CheckpointStore, File) -> Unit) {
        val scope = "s3-accept-" + UUID.randomUUID().toString().take(8)
        val name = "dev.heisluka.mobakula.s3.head-checkpoint.$scope"
        val file = File(context.noBackupFilesDir, "s3-checkpoints/$scope.sealed")
        val store = S3CheckpointStore(context, scope)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            assertFalse(file.exists())
            assertFalse(keys.containsAlias(name))
            block(store, file)
        } finally {
            AtomicFile(file).delete()
            keys.load(null)
            if (keys.containsAlias(name)) keys.deleteEntry(name)
        }
    }

    private fun checkpoint(
        generation: Long = 1,
        manifest: String = "11111111-1111-4111-8111-111111111111",
        digest: String = "b".repeat(64),
        scopeHash: String = "a".repeat(64),
    ): ByteArray = JSONObject()
        .put("schema", 1)
        .put("scope_fingerprint", scopeHash)
        .put("generation", generation)
        .put("manifest_id", manifest)
        .put("manifest_blake3", digest)
        .toString().toByteArray(Charsets.UTF_8)

    private fun evidence(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0, Bundle().apply { putString("akula_s3_check", name) },
        )
    }

    @Test
    fun persistsSealedCheckpointAndEnforcesCompareAndSet() = isolated { store, file ->
        assertTrue(store.read() == null)
        val first = checkpoint()
        store.pinVerified(first, null)
        assertTrue(file.isFile)
        assertFalse(String(file.readBytes(), Charsets.UTF_8).contains("manifest_blake3"))
        assertArrayEquals(first, store.read())
        assertArrayEquals(first, store.read()) // real decryption on separate read

        val advanced = checkpoint(
            generation = 2,
            manifest = "22222222-2222-4222-8222-222222222222",
            digest = "c".repeat(64),
        )
        assertThrows(IOException::class.java) { store.pinVerified(advanced, null) }
        assertArrayEquals(first, store.read())
        store.pinVerified(advanced, first)
        assertArrayEquals(advanced, store.read())
        assertThrows(IOException::class.java) { store.pinVerified(first, advanced) }
        assertThrows(IOException::class.java) {
            store.pinVerified(checkpoint(generation = 2, digest = "d".repeat(64)), advanced)
        }
        assertThrows(IOException::class.java) {
            store.pinVerified(checkpoint(generation = 3, scopeHash = "d".repeat(64)), advanced)
        }
        assertArrayEquals(advanced, store.read())
        evidence("checkpoint_persisted_and_cas")
    }

    @Test
    fun tamperedCiphertextCannotBeReadOrMistakenForFirstObservation() =
        isolated { store, file ->
            val first = checkpoint()
            store.pinVerified(first, null)
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertThrows(IOException::class.java) { store.read() }
            assertThrows(IOException::class.java) {
                store.pinVerified(checkpoint(generation = 2), null)
            }
            evidence("checkpoint_tamper_rejected")
        }

    @Test
    fun missingSealedFileWithExistingKeystoreKeyIsNotFreshEnrollment() =
        isolated { store, file ->
            val first = checkpoint()
            store.pinVerified(first, null)
            assertTrue(file.delete())
            assertThrows(IOException::class.java) { store.read() }
            assertThrows(IOException::class.java) { store.pinVerified(first, null) }
            evidence("checkpoint_loss_rejected")
        }
}
