package dev.heisluka.mobakula

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

// Temporary external-file tool. This is NOT the Akula vault or File Gate.
internal const val MAX_TEXT_BYTES = 128 * 1024

internal data class OpenedMarkdown(val name: String, val text: String)

internal enum class ExportResult { VERIFIED, UNVERIFIED }

internal fun decodeBoundedUtf8(stream: InputStream, maxBytes: Int = MAX_TEXT_BYTES): String {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (count > maxBytes - output.size()) {
            throw IOException("Файл слишком большой: предел $maxBytes байт.")
        }
        output.write(buffer, 0, count)
    }
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try {
        decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw IOException("Файл не является текстом UTF-8.", error)
    }
    if ('\u0000' in text) throw IOException("Двоичный файл нельзя открыть как заметку.")
    return text
}

internal fun openMarkdown(resolver: ContentResolver, uri: Uri): OpenedMarkdown {
    val text = resolver.openInputStream(uri)?.use(::decodeBoundedUtf8)
        ?: throw IOException("Не удалось получить доступ к выбранному файлу.")
    val name = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    } catch (_: RuntimeException) {
        null
    }
    return OpenedMarkdown(name?.take(100) ?: "Текстовый файл", text)
}

/**
 * Explicit export to a user-selected SAF document, never an internal vault write.
 * A successful close is not enough to claim verified storage: read the bytes back.
 */
internal fun exportMarkdown(resolver: ContentResolver, uri: Uri, text: String): ExportResult {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size > MAX_TEXT_BYTES) throw IOException("Слишком большой текст для экспорта.")
    resolver.openOutputStream(uri, "wt")?.use { stream ->
        stream.write(bytes)
        stream.flush()
    } ?: throw IOException("Не удалось открыть файл для записи.")
    // Some document providers do not grant read-after-write. Report uncertainty.
    val readBack = runCatching {
        resolver.openInputStream(uri)?.use(::decodeBoundedUtf8)
    }.getOrNull() ?: return ExportResult.UNVERIFIED
    if (readBack != text) {
        throw IOException("Записанный файл не совпадает с исходным текстом.")
    }
    return ExportResult.VERIFIED
}
