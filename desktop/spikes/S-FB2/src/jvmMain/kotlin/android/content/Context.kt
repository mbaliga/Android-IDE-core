package android.content

import java.io.File

/**
 * SPIKE STUB -- NOT the Android class. core's SearchDriverFactory.kt has exactly one Android
 * dependency, `create(context: Context)`, which calls `context.getDatabasePath("search.db")`.
 * This 1-method stub lets the REAL, unmodified factory file compile and run on a plain JVM, and
 * marks the seam LX-3(b) ("paths interface in place of Context") has to replace for real.
 */
abstract class Context {
    abstract fun getDatabasePath(name: String): File
}
