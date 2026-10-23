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
}
