package dev.heisluka.mobakula

import java.io.IOException
import org.json.JSONObject

/** Read-only display of origin-scoped receive intents. No import grant. */
internal object S3BindingProbe {
    internal enum class State(val wire: String) {
        UNMAPPED("unmapped"), EXACT("exact"), OUTDATED("outdated"),
        PREPARED("prepared"), HELD("held");

        companion object {
            fun parse(wire: String): State = entries.singleOrNull { it.wire == wire }
                ?: throw IOException("Неизвестное состояние привязки S3")
        }
    }

    private val DOCUMENT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    internal data class Scan(
        val available: Boolean = false,
        val journalExists: Boolean = false,
        val exact: Int = 0,
        val outdated: Int = 0,
        val prepared: Int = 0,
        val held: Int = 0,
        val unmapped: Int = 0,
        val states: Map<String, State> = emptyMap(),
    )

    internal fun inspect(session: String, head: ByteArray, candidates: Int): Scan {
        val response = JSONObject(NativeBridge.inspectS3BindingJournalProbe(session, head))
        if (!response.optBoolean("ok", false)) {
            throw IOException("Невозможно проверить локальный журнал S3")
        }
        val data = response.getJSONObject("data")
        return validate(
            Scan(
                available = data.getBoolean("bindingScanAvailable"),
                journalExists = data.getBoolean("journalExists"),
                exact = data.getInt("exactBindings"),
                outdated = data.getInt("outdatedBindings"),
                prepared = data.getInt("preparedIntents"),
                held = data.getInt("onHold"),
                unmapped = data.getInt("unmapped"),
                states = parseStates(data.getJSONArray("documentStates")),
            ),
            scanned = data.getInt("scannedDocuments"),
            candidates = candidates,
            ownerVerified = data.optBoolean("remoteOwnerVerified", true),
            importAuthorized = data.optBoolean("importAuthorized", true),
            synced = data.optBoolean("synced", true),
        )
    }

    private fun parseStates(rows: org.json.JSONArray): Map<String, State> {
        if (rows.length() > 16) throw IOException("Слишком много S3-кандидатов")
        val result = linkedMapOf<String, State>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val id = row.getString("documentId")
            if (!DOCUMENT_ID.matches(id) ||
                result.put(id, State.parse(row.getString("state"))) != null) {
                throw IOException("Повторяющийся или недостоверный S3 DocumentId")
            }
        }
        return result
    }

    internal fun validate(
        result: Scan, scanned: Int, candidates: Int,
        ownerVerified: Boolean, importAuthorized: Boolean, synced: Boolean,
    ): Scan {
        val numbers = listOf(
            result.exact, result.outdated, result.prepared, result.held, result.unmapped
        )
        val classified = listOf(
            State.EXACT, State.OUTDATED, State.PREPARED, State.HELD, State.UNMAPPED
        ).map { kind -> result.states.values.count { it == kind } }
        if (ownerVerified || importAuthorized || synced ||
            candidates !in 0..16 || scanned !in 0..16 || numbers.any { it < 0 } ||
            (result.available && (scanned != candidates ||
                numbers.sumOf { it.toLong() } != scanned.toLong() ||
                result.states.size != scanned || numbers != classified ||
                result.states.keys.any { !DOCUMENT_ID.matches(it) })) ||
            (!result.available && (scanned != 0 || result.journalExists ||
                numbers.any { it != 0 } || result.states.isNotEmpty())) ||
            (!result.journalExists && numbers.take(4).any { it != 0 })
        ) {
            throw IOException("Недостоверный журнал привязок S3")
        }
        return result
    }
}
