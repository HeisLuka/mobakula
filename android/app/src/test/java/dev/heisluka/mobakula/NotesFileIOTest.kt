package dev.heisluka.mobakula

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotesFileIOTest {
    @Test
    fun utf8AndEmojiRoundTrip() {
        val message = "# Заметка\nТекст 🦈 и 日本語\n"
        val restored = readUtf8Bounded(ByteArrayInputStream(message.toByteArray(Charsets.UTF_8)))
        assertEquals(message, restored)
    }

    @Test
    fun largeFileIsRejectedRatherThanTruncated() {
        assertThrows(IOException::class.java) {
            readUtf8Bounded(ByteArrayInputStream("hello".toByteArray()), limit = 4)
        }
    }

    @Test
    fun malformedUtf8IsNotSilentlyReplaced() {
        assertThrows(IOException::class.java) {
            readUtf8Bounded(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
    }

    @Test
    fun binaryNulByteIsRejected() {
        assertThrows(IOException::class.java) {
            readUtf8Bounded(ByteArrayInputStream(byteArrayOf(0x61, 0, 0x62)))
        }
    }
}
