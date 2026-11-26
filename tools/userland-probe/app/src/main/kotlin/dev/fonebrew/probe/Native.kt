package dev.fonebrew.probe

/** JNI helpers (probe_jni.c). Loaded lazily so a load failure becomes an UNKNOWN result, not a crash. */
object Native {
    @Volatile private var loadError: String? = null
    private var loaded = false

    @Synchronized
    fun ensureLoaded(): String? {
        if (!loaded) {
            loaded = true
            try { System.loadLibrary("probe_jni") } catch (t: Throwable) { loadError = "$t" }
        }
        return loadError
    }

    @JvmStatic external fun ptraceProbe(): String
    @JvmStatic external fun pvrErrno(): String
    @JvmStatic external fun unshareUserNs(): String
    @JvmStatic external fun mmapExec(path: String): String
}

/** Target of check (iv): the JNI symbol lives only in libprobe_dl.so, which the probe copies and loads by path. */
object DlProbe {
    @JvmStatic external fun answer(): Int
}
