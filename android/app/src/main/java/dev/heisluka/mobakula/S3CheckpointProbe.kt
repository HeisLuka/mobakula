package dev.heisluka.mobakula

import java.io.IOException
import org.json.JSONObject

/** JNI compares verified immutable ancestry with the Keystore-sealed pin. */
internal object S3CheckpointProbe {
    class CheckpointMismatch : IOException("Локальная история S3 не совпадает")

    internal data class Assessment(
        val relation: String,
        val candidate: ByteArray,
        /** Exact previous plaintext checked by native code; never guessed. */
        val expectedPrior: ByteArray?,
    )

    fun inspect(
        session: String, currentHead: ByteArray, ancestry: Array<ByteArray>,
        store: S3CheckpointStore,
    ): Assessment {
        val prior = store.read()
        try {
            val response = JSONObject(NativeBridge.checkS3CheckpointProbe(
                session, currentHead, ancestry, prior ?: ByteArray(0)
            ))
            if (!response.optBoolean("ok", false)) throw CheckpointMismatch()
            val data = response.getJSONObject("data")
            val relation = data.getString("relation")
            if (relation !in setOf("first", "unchanged", "advanced")
                || data.optBoolean("remoteOwnerVerified", true)
                || data.optBoolean("importAuthorized", true)
                || data.optBoolean("synced", true)
                || data.getBoolean("hadLocalCheckpoint") != (prior != null)
                || (relation == "first") != (prior == null)
            ) {
                throw CheckpointMismatch()
            }
            val candidate = data.getString("checkpointCandidate")
                .toByteArray(Charsets.UTF_8)
            val content = JSONObject(String(candidate, Charsets.UTF_8))
            if (candidate.isEmpty() || candidate.size > 512
                || data.getString("sourceScope") != content.getString("scope_fingerprint")
                || content.optLong("generation", 0) < 1
            ) {
                throw CheckpointMismatch()
            }
            return Assessment(relation, candidate, prior?.clone())
        } finally {
            prior?.fill(0)
        }
    }
}
