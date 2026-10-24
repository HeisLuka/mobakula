package dev.heisluka.mobakula

/** UI-to-Rust wire contract. Rust holds File Gate and note persistence. */
internal object NativeBridge {
    init {
        System.loadLibrary("mobakula_mobile")
    }

    external fun apiVersion(): Int

    /** JSON command; errors are encoded as JSON, not implicit success. */
    external fun request(payload: String): String

    /** Offline-only .akula-sync V1 inspection. Never put the password in request(JSON). */
    external fun inspectS3Invitation(encrypted: ByteArray, password: CharArray): String

    /** Temporary native-only credential session for an explicit read-only S3 probe. */
    external fun beginS3ReadProbe(encrypted: ByteArray, password: CharArray): String
    external fun signS3ReadProbe(session: String, method: String, manifestId: String,
                                 ifMatch: String, utcStamp: String): String
    external fun signS3RevisionProbe(session: String, revisionId: String,
                                     method: String, ifMatch: String, utcStamp: String): String
    external fun verifyS3ReadProbe(session: String, head: ByteArray,
                                   manifests: Array<ByteArray>): String
    external fun verifyS3IndexProbe(session: String, head: ByteArray,
                                      latestManifest: ByteArray,
                                      shards: Array<ByteArray>): String
    external fun verifyS3RevisionsProbe(session: String, head: ByteArray,
                                        revisions: Array<ByteArray>): String
    external fun signS3BlobProbe(session: String, blobHash: String,
                                 method: String, ifMatch: String, utcStamp: String): String
    external fun verifyS3BlobsProbe(session: String, head: ByteArray,
                                    blobs: Array<ByteArray>): String
    external fun inspectS3LocalSlots(session: String, head: ByteArray): String
    external fun checkS3CheckpointProbe(session: String, head: ByteArray,
                                        manifests: Array<ByteArray>, prior: ByteArray): String
    external fun endS3ReadProbe(session: String): String
}
