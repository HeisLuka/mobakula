package dev.heisluka.mobakula

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Sensitive values must never enter JSON, SQLite, notes, logs or Compose saved state. */
internal class UnsealedS3Invitation(
    val encryptedInvitation: ByteArray,
    val password: CharArray,
) : Closeable {
    override fun close() {
        encryptedInvitation.fill(0)
        password.fill('\u0000')
    }
}

/**
 * The ONLY durable representation is an AES-GCM encrypted blob under noBackupFilesDir.
 * Its independent, non-exportable key is held by Android Keystore. A valid invitation
 * is NOT an authenticated S3 owner, verified genesis or File Gate authorization.
 *
 * One explicit profile per Android device for S3 v0. No silent overwrite or migration.
 * All operations are blocking and must be dispatched to Dispatchers.IO by callers.
 */
internal class S3ProtectedInviteStore(context: Context, private val scope: String = "primary") {
    companion object {
        private const val MAGIC = 0x414B5332 // AKS2, only the LOCAL wrapper; remote .akula-sync is V1.
        private const val VERSION = 1
        private const val INNER_MAGIC = 0x414B5349 // AKSI
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val INVITE_MAX = 16 * 1024
        private const val PASSWORD_MAX_CHARS = 1024
        private const val PASSWORD_MAX_BYTES = PASSWORD_MAX_CHARS * 4
        private const val MAX_FILE = INVITE_MAX + PASSWORD_MAX_BYTES + 256
        private val AAD = "dev.heisluka.mobakula:s3-invite-at-rest:v1".toByteArray(Charsets.US_ASCII)
        private val LOCK = Any()
        private val SCOPE = Regex("[a-z0-9-]{1,40}")
    }

    init {
        require(SCOPE.matches(scope)) { "Invalid protected profile scope" }
    }

    private val alias = "dev.heisluka.mobakula.s3.invite.$scope"
    private val atomic = AtomicFile(
        java.io.File(context.noBackupFilesDir, "s3-profiles/$scope.sealed")
    )

    fun exists(): Boolean = synchronized(LOCK) { atomic.baseFile.exists() }

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey = (keystore().getKey(alias, null) as? SecretKey)
        ?: throw IOException("Ключ сохранённого S3-профиля недоступен")

    private fun newOrExistingKey(): SecretKey {
        val vault = keystore()
        (vault.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun encodePassword(password: CharArray): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(CharBuffer.wrap(password))
        return try {
            ByteArray(buffer.remaining()).also(buffer::get)
        } finally {
            if (buffer.hasArray()) buffer.array().fill(0)
        }
    }

    private fun encodePlaintext(invitation: ByteArray, password: CharArray): ByteArray {
        if (invitation.isEmpty() || invitation.size > INVITE_MAX
            || password.isEmpty() || password.size > PASSWORD_MAX_CHARS) {
            throw IOException("Размер S3-приглашения или пароля недопустим")
        }
        val passwordBytes = encodePassword(password)
        return try {
            if (passwordBytes.size !in 20..PASSWORD_MAX_BYTES) {
                throw IOException("Пароль не соответствует контракту .akula-sync")
            }
            ByteBuffer.allocate(16 + invitation.size + passwordBytes.size)
                .putInt(INNER_MAGIC).putInt(VERSION)
                .putInt(invitation.size).put(invitation)
                .putInt(passwordBytes.size).put(passwordBytes).array()
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun field(buffer: ByteBuffer, max: Int): ByteArray {
        if (buffer.remaining() < 4) throw IOException("Повреждённый S3-профиль")
        val length = buffer.int
        if (length < 1 || length > max || length > buffer.remaining()) {
            throw IOException("Недопустимая длина поля S3-профиля")
        }
        return ByteArray(length).also(buffer::get)
    }

    private fun decodePlaintext(plaintext: ByteArray): UnsealedS3Invitation {
        val bytes = ByteBuffer.wrap(plaintext)
        if (bytes.remaining() < 16 || bytes.int != INNER_MAGIC || bytes.int != VERSION) {
            throw IOException("Неподдерживаемый формат S3-профиля")
        }
        val invitation = field(bytes, INVITE_MAX)
        try {
            val passwordBytes = field(bytes, PASSWORD_MAX_BYTES)
            try {
                if (bytes.hasRemaining()) throw IOException("Лишние байты S3-профиля")
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val chars = decoder.decode(ByteBuffer.wrap(passwordBytes))
                val password = CharArray(chars.remaining())
                chars.get(password)
                if (chars.hasArray()) chars.array().fill('\u0000')
                if (password.isEmpty() || password.size > PASSWORD_MAX_CHARS) {
                    password.fill('\u0000')
                    throw IOException("Пароль S3-профиля имеет неверный размер")
                }
                return UnsealedS3Invitation(invitation, password)
            } finally {
                passwordBytes.fill(0)
            }
        } catch (cause: Exception) {
            invitation.fill(0)
            throw cause
        }
    }

    fun saveNew(encryptedInvitation: ByteArray, password: CharArray) = synchronized(LOCK) {
        if (exists()) throw IOException("Профиль уже сохранён. Удалите его перед заменой")
        val plaintext = encodePlaintext(encryptedInvitation, password)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, newOrExistingKey())
            cipher.updateAAD(AAD)
            val encrypted = cipher.doFinal(plaintext)
            val iv = cipher.iv
            if (iv.size != IV_BYTES) throw IOException("Неожиданная длина GCM nonce")
            val record = ByteBuffer.allocate(16 + iv.size + encrypted.size)
                .putInt(MAGIC).putInt(VERSION)
                .putInt(iv.size).put(iv)
                .putInt(encrypted.size).put(encrypted).array()
            if (record.size > MAX_FILE) throw IOException("S3-профиль слишком большой")
            atomic.baseFile.parentFile?.let { dir ->
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Нет каталога S3")
            }
            val output = atomic.startWrite()
            try {
                output.write(record)
                atomic.finishWrite(output)
            } catch (cause: Exception) {
                atomic.failWrite(output)
                throw cause
            } finally {
                record.fill(0) // Ciphertext is not secret, but clear temporary copies.
                encrypted.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun open(): UnsealedS3Invitation = synchronized(LOCK) {
        if (!exists() || atomic.baseFile.length() !in 1..MAX_FILE.toLong()) {
            throw IOException("Нет защищённого S3-профиля или файл повреждён")
        }
        val record = atomic.openRead().use { input ->
            val result = input.readBytes()
            if (result.size > MAX_FILE) throw IOException("Превышен размер S3-профиля")
            result
        }
        try {
            val buffer = ByteBuffer.wrap(record)
            if (buffer.remaining() < 16 || buffer.int != MAGIC || buffer.int != VERSION) {
                throw IOException("Неподдерживаемый защищённый S3-профиль")
            }
            val iv = field(buffer, IV_BYTES)
            if (iv.size != IV_BYTES) throw IOException("Некорректный GCM nonce")
            val ciphertext = field(buffer, MAX_FILE)
            try {
                if (buffer.hasRemaining() || ciphertext.size < 16) {
                    throw IOException("Повреждённый S3-профиль")
                }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(AAD)
                val clear = try {
                    cipher.doFinal(ciphertext)
                } catch (_: javax.crypto.AEADBadTagException) {
                    throw IOException("S3-профиль не прошёл проверку целостности")
                }
                try {
                    return@synchronized decodePlaintext(clear)
                } finally {
                    clear.fill(0)
                }
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
        } finally {
            record.fill(0)
        }
    }

    fun delete() = synchronized(LOCK) {
        atomic.delete()
        val vault = keystore()
        if (vault.containsAlias(alias)) vault.deleteEntry(alias)
    }
}
