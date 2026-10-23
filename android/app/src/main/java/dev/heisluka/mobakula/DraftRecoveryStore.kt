package dev.heisluka.mobakula

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A best-effort local checkpoint, NOT a verified File Gate/HistoryService save. */
data class DraftSnapshot(val noteId: String, val baseHash: String, val text: String)
data class DraftRecoverySuggestion(val snapshot: DraftSnapshot, val changedOnDisk: Boolean)

/** Versioned, strictly bounded format; works in ordinary JVM unit tests. */
internal object DraftSnapshotCodec {
    private const val MAGIC = 0x414B4452 // AKDR
    private const val VERSION = 1
    const val MAX_TEXT_BYTES = 1024 * 1024
    const val MAX_FILE_BYTES = MAX_TEXT_BYTES + 512
    private val ID = Regex("[0-9a-fA-F-]{36}")
    private val HASH = Regex("[0-9a-fA-F]{64}")

    fun encode(snapshot: DraftSnapshot): ByteArray {
        require(ID.matches(snapshot.noteId) && HASH.matches(snapshot.baseHash)) {
            "Invalid note ID or base revision"
        }
        val id = snapshot.noteId.toByteArray(Charsets.UTF_8)
        val hash = snapshot.baseHash.toByteArray(Charsets.UTF_8)
        val text = snapshot.text.toByteArray(Charsets.UTF_8)
        require(text.size <= MAX_TEXT_BYTES) { "Draft exceeds 1 MiB" }
        return ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                for (field in arrayOf(id, hash, text)) {
                    out.writeInt(field.size)
                    out.write(field)
                }
            }
            buffer.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): DraftSnapshot {
        if (bytes.size > MAX_FILE_BYTES) throw IOException("Draft checkpoint exceeds limit")
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw IOException("Unsupported draft checkpoint format")
            }
            fun field(limit: Int): String {
                val size = input.readInt()
                if (size < 0 || size > limit || size > input.available()) {
                    throw IOException("Truncated or oversized checkpoint field")
                }
                val data = ByteArray(size)
                input.readFully(data)
                return Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString()
            }
            val id = field(128)
            val hash = field(128)
            val text = field(MAX_TEXT_BYTES)
            if (input.available() != 0 || !ID.matches(id) || !HASH.matches(hash)) {
                throw IOException("Invalid draft checkpoint identity or trailing data")
            }
            return DraftSnapshot(id, hash, text)
        }
    }
}

/**
 * Isolated backup in noBackupFilesDir. File paths are SHA-256 hashes, not note paths.
 * A monotonic epoch invalidates queued asynchronous writes on save/discard/edit.
 * Mutex + AtomicFile prevent stale writers from resurrecting deleted checkpoints.
 */
internal class DraftRecoveryStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "draft-checkpoints")
    private val mutex = Mutex()
    private val epoch = AtomicLong()

    fun invalidate(): Long = epoch.incrementAndGet()
    fun epoch(): Long = epoch.get()

    private fun file(id: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$digest.draft"))
    }

    private fun readDirect(id: String): DraftSnapshot? {
        val atomic = file(id)
        if (!atomic.baseFile.exists()) return null
        if (atomic.baseFile.length() > DraftSnapshotCodec.MAX_FILE_BYTES) {
            throw IOException("Draft checkpoint exceeds limit")
        }
        val value = atomic.openRead().use { DraftSnapshotCodec.decode(it.readBytes()) }
        if (value.noteId != id) throw IOException("Draft checkpoint belongs to another note")
        return value
    }

    private fun writeDirect(snapshot: DraftSnapshot): Boolean {
        // An older backup from a different base revision must never be overwritten.
        val previous = readDirect(snapshot.noteId)
        if (previous != null && previous.baseHash != snapshot.baseHash) return false
        val data = DraftSnapshotCodec.encode(snapshot)
        if (previous == snapshot) return true
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create draft directory")
        }
        val atomic = file(snapshot.noteId)
        val stream = atomic.startWrite()
        try {
            stream.write(data)
            atomic.finishWrite(stream)
        } catch (cause: Exception) {
            atomic.failWrite(stream)
            throw cause
        }
        return true
    }

    suspend fun load(id: String): DraftSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock { readDirect(id) }
    }

    suspend fun checkpoint(snapshot: DraftSnapshot, expectedEpoch: Long): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (epoch.get() != expectedEpoch) false else writeDirect(snapshot)
            }
        }

    suspend fun writeBeforeSave(snapshot: DraftSnapshot): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { writeDirect(snapshot) }
    }

    suspend fun clearIfRevision(id: String, baseHash: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (readDirect(id)?.baseHash != baseHash) false
                else {
                    file(id).delete()
                    true
                }
            }
        }
}
