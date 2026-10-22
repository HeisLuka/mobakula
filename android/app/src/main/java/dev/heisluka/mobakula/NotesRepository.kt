package dev.heisluka.mobakula

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class NoteDocument(val id: String, val content: String, val hash: String)
data class NoteHeader(val id: String, val title: String)

class NoteFailure(val code: String, details: String) : Exception("$code: $details")

/** Android transport only: all note reads/writes are implemented by Akula Rust. */
class NotesRepository(context: Context) {
    private val appDataRoot = context.filesDir.absolutePath
    private val resolver = context.contentResolver

    private fun request(arguments: JSONObject): Any {
        val raw = NativeBridge.request(arguments.toString())
        val response = JSONObject(raw)
        if (!response.getBoolean("ok")) {
            throw NoteFailure(
                response.optString("code", "unknown"),
                response.optString("message", "Native request failed"),
            )
        }
        return response.get("data")
    }

    private fun note(value: JSONObject) = NoteDocument(
        value.getString("id"),
        value.getString("content"),
        value.getString("hash"),
    )

    suspend fun initialize() = withContext(Dispatchers.IO) {
        request(JSONObject().put("action", "init").put("root", appDataRoot))
        Unit
    }

    suspend fun list(): List<NoteHeader> = withContext(Dispatchers.IO) {
        val array = request(JSONObject().put("action", "list")) as JSONArray
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            NoteHeader(item.getString("id"), item.getString("title"))
        }
    }

    private fun createRaw(content: String): NoteDocument {
        val response = request(
            JSONObject().put("action", "create").put("content", content),
        ) as JSONObject
        return note(response)
    }

    suspend fun create(): NoteDocument = withContext(Dispatchers.IO) {
        createRaw("# Новая заметка\n")
    }

    suspend fun importFile(uri: Uri): NoteDocument = withContext(Dispatchers.IO) {
        createRaw(importNoteText(resolver, uri))
    }

    suspend fun exportFile(expected: NoteDocument, uri: Uri): ExportStatus =
        withContext(Dispatchers.IO) {
            val current = note(
                request(JSONObject().put("action", "read").put("id", expected.id)) as JSONObject,
            )
            if (current.hash != expected.hash) {
                throw NoteFailure("conflict", "Заметка изменилась. Откройте её заново.")
            }
            exportNoteText(resolver, uri, current.content)
        }

    suspend fun read(id: String): NoteDocument = withContext(Dispatchers.IO) {
        note(request(JSONObject().put("action", "read").put("id", id)) as JSONObject)
    }

    suspend fun save(note: NoteDocument, content: String): NoteDocument =
        withContext(Dispatchers.IO) {
            val response = request(
                JSONObject().put("action", "save")
                    .put("id", note.id)
                    .put("expected_hash", note.hash)
                    .put("content", content),
            ) as JSONObject
            note(response)
        }
}
