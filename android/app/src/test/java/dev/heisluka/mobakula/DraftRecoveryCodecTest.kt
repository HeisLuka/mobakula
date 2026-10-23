package dev.heisluka.mobakula

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DraftRecoveryCodecTest {
    private val noteId = "b794a486-e0b1-4ae9-bc67-a26fe30a8110"
    private val revision = "a".repeat(64)
    private val sample = DraftSnapshot(
        noteId, revision, "# Заметка\nРусский 😀, 你好, a\u0301, مرحبا\n",
    )

    @Test
    fun roundTripPreservesMultilingualContentAndExactBaseRevision() {
        assertEquals(sample, DraftSnapshotCodec.decode(DraftSnapshotCodec.encode(sample)))
    }

    @Test
    fun supportsEmptyDraftAndEntireOneMibLimit() {
        val empty = sample.copy(text = "")
        assertEquals(empty, DraftSnapshotCodec.decode(DraftSnapshotCodec.encode(empty)))
        val max = sample.copy(text = "x".repeat(DraftSnapshotCodec.MAX_TEXT_BYTES))
        assertEquals(max, DraftSnapshotCodec.decode(DraftSnapshotCodec.encode(max)))
        assertThrows(IllegalArgumentException::class.java) {
            DraftSnapshotCodec.encode(max.copy(text = max.text + "x"))
        }
    }

    @Test
    fun rejectsMalformedIdsAndRevisionsBeforeWriting() {
        for (value in listOf("../other", "", "missing")) {
            assertThrows(IllegalArgumentException::class.java) {
                DraftSnapshotCodec.encode(sample.copy(noteId = value))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DraftSnapshotCodec.encode(sample.copy(baseHash = "wrong"))
        }
    }

    @Test
    fun corruptedTruncatedOrExtendedCheckpointCannotRestore() {
        val valid = DraftSnapshotCodec.encode(sample)
        for (bytes in listOf(
            valid.copyOfRange(0, valid.size - 2),
            valid.copyOfRange(0, 5),
            valid.copyOf().apply { this[0] = 0 },
            valid + byteArrayOf(0),
        )) {
            assertThrows(Exception::class.java) { DraftSnapshotCodec.decode(bytes) }
        }
        assertThrows(IOException::class.java) {
            DraftSnapshotCodec.decode(ByteArray(DraftSnapshotCodec.MAX_FILE_BYTES + 1))
        }
    }

    @Test
    fun rejectsMalformedUtf8InsteadOfSilentlyReplacingCharacters() {
        val encoded = DraftSnapshotCodec.encode(sample.copy(text = "a"))
        val corrupt = encoded.copyOf()
        corrupt[corrupt.lastIndex] = 0xFF.toByte()
        assertThrows(Exception::class.java) { DraftSnapshotCodec.decode(corrupt) }
        assertArrayEquals(encoded, DraftSnapshotCodec.encode(sample.copy(text = "a")))
    }
}
