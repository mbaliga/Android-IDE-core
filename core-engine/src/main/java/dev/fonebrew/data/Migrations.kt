package dev.fonebrew.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Real Room [Migration] objects for [AppDatabase], committed as part of retiring
 * `fallbackToDestructiveMigration()` (the #1 data-loss bug across two prior audits — see the
 * policy comment at the `Room.databaseBuilder` call site in `di/AppContainer.kt`).
 *
 * [MIGRATION_10_11] is deliberately a no-op: v10 -> v11 changes no entity, column, or table (see
 * the v10->v11 comment on [AppDatabase]'s `@Database` annotation) — it exists only to prove the
 * real migration path end-to-end before any future bump actually needs one. A no-op body is
 * correct here specifically because v11's exported schema (core-engine/schemas/) is structurally
 * identical to v10's (10.json and 11.json differ only in the version number; the migration test
 * pins that); Room validates the post-migration database against the target version's
 * schema, and an unchanged schema needs no statements to reach it.
 *
 * Every future schema bump MUST add its own `Migration` object here (plus a migration test) —
 * see AppContainer.kt's policy comment. Never fall back to destructive migration again.
 */
val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    // Both overloads are implemented (not just one) because Room's default `migrate(SQLiteConnection)`
    // only forwards to `migrate(SupportSQLiteDatabase)` when the connection wraps a classic
    // SupportSQLiteDatabase — i.e. only on the production path below, which never calls
    // `.setDriver(...)`. A driver-based caller (this migration's own JVM test, which has to use
    // `.setDriver(BundledSQLiteDriver())` since there's no device/Robolectric here) gets a real,
    // non-adapted SQLiteConnection and would hit Migration's `NotImplementedError` default if this
    // overload weren't overridden too. Both bodies are no-ops for the same reason either way:
    // v10 -> v11 changes no schema (see the KDoc above).
    override fun migrate(db: SupportSQLiteDatabase) {
        // No-op: no schema change between v10 and v11 (see KDoc above).
    }

    override fun migrate(connection: SQLiteConnection) {
        // No-op: no schema change between v10 and v11 (see KDoc above).
    }
}
