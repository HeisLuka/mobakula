package dev.heisluka.mobakula

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** One explicitly selected Markdown note, NOT an S3 sync or trusted account. */
internal object S3ManualImport {
    private const val MAX_CANDIDATES = 16
    private const val MAX_NOTE_BYTES = 1024 * 1024
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val HASH = Regex("[0-9a-f]{64}")

    internal data class Candidate(
        val documentId: String,
        val revisionId: String,
        val blobHash: String,
        val bytes: Int,
    )

    internal fun parseCandidates(proof: JSONObject): List<Candidate> {
        val claimed = proof.getInt("receiveCandidates")
        val refs = proof.getJSONArray("candidateRefs")
        if (claimed !in 0..MAX_CANDIDATES || refs.length() != claimed) {
            throw IOException("Недостоверный список Markdown из S3")
        }
        val items = List(refs.length()) { i ->
            val item = refs.getJSONObject(i)
            Candidate(
                item.getString("documentId"), item.getString("revisionId"),
                item.getString("blobHash"), item.getInt("bytes"),
            )
        }
        return validateCandidates(items, claimed)
    }

    /** Pure validator usable by ordinary JVM unit tests without Android JSON. */
    internal fun validateCandidates(items: List<Candidate>, claimed: Int): List<Candidate> {
        if (claimed !in 0..MAX_CANDIDATES || items.size != claimed) {
            throw IOException("Недостоверный список Markdown из S3")
        }
        val seen = HashSet<String>()
        if (items.any { candidate ->
                !UUID.matches(candidate.documentId) ||
                    !UUID.matches(candidate.revisionId) ||
                    !HASH.matches(candidate.blobHash) ||
                    candidate.bytes !in 0..MAX_NOTE_BYTES ||
                    !seen.add(candidate.documentId)
            }) {
            throw IOException("Повторяющийся или повреждённый S3 документ")
        }
        return items
    }

    /** Called only after a *second* full read and explicit per-note consent. */
    internal fun importSelected(
        session: String, pinnedHead: ByteArray, selectedDocumentId: String,
        candidates: List<Candidate>, blobRefs: JSONArray, received: List<ByteArray>,
    ): String {
        val selected = candidates.singleOrNull { it.documentId == selectedDocumentId }
            ?: throw IOException("Выбранная заметка отсутствует в проверенном S3")
        if (blobRefs.length() != received.size) {
            throw IOException("Содержимое S3 изменилось до импорта")
        }
        val matching = (0 until blobRefs.length()).singleOrNull { i ->
            blobRefs.getJSONObject(i).getString("hash") == selected.blobHash
        } ?: throw IOException("Не найден подтверждённый S3 blob")
        val raw = received[matching]
        if (raw.size != selected.bytes) {
            throw IOException("Изменился размер выбранного S3 blob")
        }
        val response = JSONObject(NativeBridge.importS3VerifiedMarkdown(
            session, pinnedHead, selected.documentId, raw
        ))
        if (!response.optBoolean("ok", false)) {
            // The Rust journal might be on HOLD and the file might already
            // exist. Do not retry automatically after any failure.
            throw IOException(
                "Импорт не завершён: " + response.optString("code", "needs_review") +
                    ". Проверьте заметки и журнал, не повторяйте вслепую."
            )
        }
        val receipt = response.getJSONObject("data")
        val localId = receipt.getString("localNoteId")
        if (receipt.getString("documentId") != selected.documentId ||
            !UUID.matches(localId) ||
            !HASH.matches(receipt.getString("sourceScope")) ||
            !receipt.optBoolean("imported", false) ||
            receipt.optBoolean("synced", true) ||
            receipt.optBoolean("remoteOwnerVerified", true)
        ) {
            throw IOException("Недостоверная квитанция S3 импорта")
        }
        return localId
    }
}
