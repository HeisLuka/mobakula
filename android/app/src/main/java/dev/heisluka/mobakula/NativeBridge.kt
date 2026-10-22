package dev.heisluka.mobakula

/** M0 JNI proof only. This interface has no permission to access the vault. */
internal object NativeBridge {
    init {
        System.loadLibrary("mobakula_mobile")
    }

    external fun apiVersion(): Int
}
