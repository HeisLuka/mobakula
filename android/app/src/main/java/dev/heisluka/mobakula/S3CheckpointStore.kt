package dev.heisluka.mobakula

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Explicitly pinned LOCAL TOFU observation, not proof of S3 publisher identity.
 * The anchor is AES-GCM sealed by a nonexportable Android Keystore key, under
 * noBackupFilesDir. A privileged rollback of BOTH local key and ciphertext is
 * outside the guarantee; this is not a hardware monotonic counter.
 */
internal class S3CheckpointStore(context: Context, private val scope: String = "primary") {
    companion object {
        private const val MAGIC = 0x414B4348 // AKCH
        private const val VERSION = 1
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        private const val MAX_ANCHOR = 512
        private const val MAX_FILE = 2048
        private val LOCK = Any()
        private val SAFE_SCOPE = Regex("[a-z0-9-]{1,40}")
        private val HASH = Regex("[0-9a-f]{64}")
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }

    init { require(SAFE_SCOPE.matches(scope)) }
    private val alias = "dev.heisluka.mobakula.s3.head-checkpoint.$scope"
    private val atomic = AtomicFile(
        File(context.noBackupFilesDir, "s3-checkpoints/$scope.sealed")
    )
    private val aad = "dev.heisluka.mobakula:s3-head-checkpoint:v1:$scope"
        .toByteArray(Charsets.US_ASCII)

    private data class Anchor(
        val source: String, val generation: Long, val manifest: String, val digest: String,
    )

    private fun parse(bytes: ByteArray): Anchor {
        if (bytes.isEmpty() || bytes.size > MAX_ANCHOR) {
            throw IOException("Недопустимая контрольная точка S3")
        }
        val json = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            throw IOException("Повреждена контрольная точка S3")
        }
        if (json.length() != 5 || json.optInt("schema", -1) != VERSION) {
            throw IOException("Неподдерживаемая контрольная точка S3")
        }
        val source = json.optString("scope_fingerprint", "")
        val manifest = json.optString("manifest_id", "")
        val digest = json.optString("manifest_blake3", "")
        val generation = json.optLong("generation", 0)
        if (!HASH.matches(source) || !UUID.matches(manifest)
            || !HASH.matches(digest) || generation < 1
        ) {
            throw IOException("Недопустимое происхождение контрольной точки S3")
        }
        return Anchor(source, generation, manifest, digest)
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getExistingKey(): SecretKey? =
        keyStore().getKey(alias, null) as? SecretKey

    private fun createOrGetKey(): SecretKey {
        getExistingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build())
        return generator.generateKey()
    }

    /** Absence is fresh only when BOTH the key and sealed file are absent. */
    fun read(): ByteArray? = synchronized(LOCK) {
        val present = atomic.baseFile.exists() ||
            File(atomic.baseFile.path + ".bak").exists()
        val key = getExistingKey()
        if (!present) {
            if (key != null) {
                throw IOException("Сохранённая контрольная точка S3 исчезла")
            }
            return@synchronized null
        }
        if (key == null) throw IOException("Ключ контрольной точки S3 утрачен")
        val buffer = ByteArrayOutputStream()
        atomic.openRead().use { stream ->
            val part = ByteArray(512)
            var countZero = 0
            while (true) {
                val n = stream.read(part)
                if (n < 0) break
                if (n == 0) {
                    if (++countZero > 8) throw IOException("Невозможно прочитать S3 checkpoint")
                    continue
                }
                countZero = 0
                if (buffer.size() + n > MAX_FILE) throw IOException("S3 checkpoint слишком большой")
                buffer.write(part, 0, n)
            }
        }
        val stored = buffer.toByteArray()
        try {
            val read = ByteBuffer.wrap(stored)
            if (read.remaining() < 20 || read.int != MAGIC || read.int != VERSION) {
                throw IOException("Неподдерживаемый формат S3 checkpoint")
            }
            val ivLen = read.int
            if (ivLen != IV_SIZE || read.remaining() < ivLen + 4 + 16) {
                throw IOException("Повреждён nonce контрольной точки")
            }
            val iv = ByteArray(ivLen).also { read.get(it) }
            val n = read.int
            if (n !in 16..MAX_FILE || n != read.remaining()) {
                throw IOException("Повреждена длина контрольной точки")
            }
            val ciphertext = ByteArray(n).also { read.get(it) }
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(aad)
                val clear = try {
                    cipher.doFinal(ciphertext)
                } catch (_: java.security.GeneralSecurityException) {
                    throw IOException("S3 checkpoint не прошёл проверку целостности")
                }
                try {
                    parse(clear) // Reject malformed plaintext even if GCM was valid.
                    return@synchronized clear
                } catch (cause: Exception) {
                    clear.fill(0)
                    throw cause
                }
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
        } finally {
            stored.fill(0)
        }
    }

    /**
     * Only called after a complete S3 content proof AND explicit button click.
     * Atomic compare-and-set rejects any other local writer since the proof.
     * It never writes a note, File Gate baseline, or S3 object.
     */
    fun pinVerified(candidate: ByteArray, expectedPrior: ByteArray?) = synchronized(LOCK) {
        val new = parse(candidate)
        val current = read()
        try {
            if (!(current?.contentEquals(expectedPrior ?: ByteArray(0))
                    ?: (expectedPrior == null))
            ) {
                throw IOException("Контрольная точка изменилась: повторите S3 проверку")
            }
            if (current != null) {
                val old = parse(current)
                if (old.source != new.source || new.generation < old.generation
                    || (new.generation == old.generation &&
                        (new.manifest != old.manifest || new.digest != old.digest))
                ) {
                    throw IOException("Откат или конфликт S3 checkpoint")
                }
            }
            if (candidate.contentEquals(current ?: ByteArray(0))) return@synchronized
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, createOrGetKey())
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(candidate)
            val iv = cipher.iv
            if (iv.size != IV_SIZE) throw IOException("Недопустимый nonce S3")
            val output = ByteBuffer.allocate(16 + iv.size + ciphertext.size)
                .putInt(MAGIC).putInt(VERSION)
                .putInt(iv.size).put(iv)
                .putInt(ciphertext.size).put(ciphertext).array()
            try {
                if (output.size > MAX_FILE) throw IOException("S3 checkpoint слишком большой")
                atomic.baseFile.parentFile?.let {
                    if (!it.isDirectory && !it.mkdirs()) throw IOException("Нет каталога S3")
                }
                val stream = atomic.startWrite()
                try {
                    stream.write(output)
                    atomic.finishWrite(stream)
                } catch (cause: Exception) {
                    atomic.failWrite(stream)
                    throw cause
                }
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
                output.fill(0)
            }
        } finally {
            current?.fill(0)
        }
    }
}
