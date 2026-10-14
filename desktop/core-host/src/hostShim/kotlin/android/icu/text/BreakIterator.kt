// HOST-LANE COMPILE-TIME STAND-IN -- NOT A PORT, NOT A FAKE OF BEHAVIOUR.
//
// Why this file exists. domain/search/Segmenter.kt (mapped UNCHANGED by this lane) references
// android.icu.text.BreakIterator by fully-qualified name on five code lines (IcuBoundarySource and the
// defaultSource probe). The porting plan's "no android.* import" grep missed it because there is no
// `import` line. On a plain JVM that class does not exist, so the file cannot compile without
// something to resolve against.
//
// What this is. The same thing android.jar is to the Android unit-test lane: a stub whose entry
// point THROWS. Segmenter was written for exactly this: its `defaultSource` probes
// `android.icu.text.BreakIterator.getWordInstance(Locale.ROOT)` and, on any Throwable, falls back to
// JavaTextBoundarySource (java.text.BreakIterator). Under AGP's unit tests the android.jar stub
// throws "Method getWordInstance ... not mocked"; here this stub throws
// UnsupportedOperationException. Same probe, same fallback, same JDK code path, so the mapped
// Segmenter/search tests exercise the identical branch they exercise in the Android gate.
//
// What it must never become. A working ICU. If someone gave this real behaviour, the host lane would
// silently stop testing what Android tests. It has NO implementation by design (every instance
// method throws, the only factory throws), and desktop/README.md lists it as a lane limitation.
//
// IOS-1 note. This stub disappears when Segmenter gets an expect/actual BoundarySource (PORTABILITY.md
// lists the android.icu references). Lives in package android.icu.text (legal on the JVM; only java.*
// is reserved), compiled into :core-host only -- never into anything Android builds.
package android.icu.text

import java.util.Locale

abstract class BreakIterator {
    abstract fun setText(text: String)
    abstract fun first(): Int
    abstract fun next(): Int

    companion object {
        const val DONE: Int = -1

        @JvmStatic
        fun getWordInstance(locale: Locale): BreakIterator =
            throw UnsupportedOperationException(
                "android.icu.text.BreakIterator is an Android-runtime API; this is the desktop host-lane " +
                    "compile-time stand-in and has no implementation (callers fall back to java.text)",
            )
    }
}
