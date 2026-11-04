package android.icu.text

import java.util.Locale

/**
 * HOST-LANE STUB, not ICU. Compile-only stand-in for the one Android API that `domain/search/
 * Segmenter.kt` names (`android.icu.text.BreakIterator`), so that file compiles unchanged on a
 * plain JVM. Every factory throws, exactly as the unmocked Android stub jar does in the root
 * module's JVM tests, so `Segmenter.defaultSource` falls back to `java.text.BreakIterator`.
 * It never segments anything. Real ICU behaviour is Android-only and owner-verified.
 */
abstract class BreakIterator {
    abstract fun setText(text: String)
    abstract fun first(): Int
    abstract fun next(): Int

    companion object {
        const val DONE: Int = -1

        @JvmStatic
        fun getWordInstance(locale: Locale): BreakIterator =
            throw UnsupportedOperationException("android.icu is not available on the host JVM (stub)")
    }
}
