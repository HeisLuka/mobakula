package dev.heisluka.mobakula

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

// The same ceiling as Rust NoteVault; text is copied once across JNI.
internal const val MAX_NOTE_TRANSFER_BYTES = 1024 * 1024

internal enum class ExportStatus { VERIFIED, UNVERIFIED }

/** Reject truncated/invalid UTF-8 and over-large or binary input before File Gate create. */
internal fun readUtf8Bounded(
    stream: InputStream,
    limit: Int = MAX_NOTE_TRANSFER_BYTES,
): String {
    require(limit > 0)
    val bytes = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var emptyReads = 0
    while (true) {
        val size = stream.read(chunk)
        if (size < 0) break
        if (size == 0) {
            emptyReads++
            if (emptyReads > 3) throw IOException("Источник не возвращает данные.")
            continue
        }
        emptyReads = 0
        if (size > limit - bytes.size()) {
            throw IOException("Файл превышает ограничение 1 МиБ.")
        }
        bytes.write(chunk, 0, size)
    }
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try {
        decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    } catch (cause: CharacterCodingException) {
        throw IOException("Требуется текст в кодировке UTF-8.", cause)
    }
    if (text.contains('\u0000')) throw IOException("Двоичные данные нельзя открыть как заметку.")
    return text
}

internal fun importNoteText(resolver: ContentResolver, uri: Uri): String {
    return resolver.openInputStream(uri)?.use(::readUtf8Bounded)
        ?: throw IOException("Не удалось прочитать выбранный файл.")
}

/**
 * Writes only an explicit user-selected SAF destination, never the Akula vault.
 * A provider may not allow readback: that result is UNVERIFIED, not saved.
 */
internal fun exportNoteText(
    resolver: ContentResolver,
    uri: Uri,
    text: String,
): ExportStatus {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size > MAX_NOTE_TRANSFER_BYTES) throw IOException("Текст превышает ограничение 1 МиБ.")
    resolver.openOutputStream(uri, "wt")?.use { output ->
        output.write(bytes)
        output.flush()
    } ?: throw IOException("Не удалось открыть выбранный файл для записи.")

    val verificationStream = try {
        resolver.openInputStream(uri)
    } catch (_: SecurityException) {
        null
    } catch (_: IOException) {
        null
    } ?: return ExportStatus.UNVERIFIED

    val restored = verificationStream.use(::readUtf8Bounded)
    if (restored != text) throw IOException("Проверка экспорта: содержимое не совпадает.")
    return ExportStatus.VERIFIED
}
