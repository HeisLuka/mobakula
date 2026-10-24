package dev.heisluka.mobakula

import java.io.IOException
import org.json.JSONObject

/** Read-only display of origin-scoped receive intents. No import grant. */
internal object S3BindingProbe {
    internal data class Scan(
        val available: Boolean = false,
        val journalExists: Boolean = false,
        val exact: Int = 0,
        val outdated: Int = 0,
        val prepared: Int = 0,
        val held: Int = 0,
        val unmapped: Int = 0,
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
            ),
            scanned = data.getInt("scannedDocuments"),
            candidates = candidates,
            ownerVerified = data.optBoolean("remoteOwnerVerified", true),
            importAuthorized = data.optBoolean("importAuthorized", true),
            synced = data.optBoolean("synced", true),
        )
    }

    internal fun validate(
        result: Scan, scanned: Int, candidates: Int,
        ownerVerified: Boolean, importAuthorized: Boolean, synced: Boolean,
    ): Scan {
        val numbers = listOf(
            result.exact, result.outdated, result.prepared, result.held, result.unmapped
        )
        if (ownerVerified || importAuthorized || synced ||
            candidates < 0 || scanned < 0 || numbers.any { it < 0 } ||
            (result.available && (scanned != candidates || numbers.sumOf { it.toLong() } != scanned.toLong())) ||
            (!result.available && (scanned != 0 || result.journalExists ||
                numbers.any { it != 0 })) ||
            (!result.journalExists && numbers.take(4).any { it != 0 })
        ) {
            throw IOException("Недостоверный журнал привязок S3")
        }
        return result
    }
}
