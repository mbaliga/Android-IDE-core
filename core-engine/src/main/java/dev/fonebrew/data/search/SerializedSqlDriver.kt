package dev.fonebrew.data.search

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.util.concurrent.locks.ReentrantLock

/**
 * Makes a [SqlDriver] safe to share between threads by running every statement, and every whole
 * transaction, under one lock.
 *
 * Why: `com.eygraber:sqldelight-androidx-driver:0.0.1` (the newest our Kotlin pin can read; see
 * `libs.versions.toml`) keeps ONE lazily-opened connection and a plain `LinkedHashMap` statement
 * cache, with no synchronization at all. [SearchDatabase] is used from several threads at once:
 * `SearchViewModel` starts four `asFlow().mapToList(Dispatchers.IO)` reads at construction while
 * `SearchIndexer` writes, `SearchProjectionCorpus` reads, and so on. Interleaved `prepare`/`step`
 * on the shared connection and statement cache corrupts native state: on a device it surfaced as
 * `SQLException: no such column: saved_search` from `SELECT * FROM saved_search` (the CI emulator
 * smoke test, a FATAL on first message send) and on the JVM host as an outright native SIGSEGV in
 * `sqlite3SrcListAssignCursors` (`SearchFileDatabaseConcurrencyTest`).
 *
 * A reentrant lock held from `newTransaction` until the transaction's commit/rollback hook also
 * stops another thread's statements from running *inside* a transaction that is not theirs.
 * Readers wait for a write transaction's duration; at this index's size that is milliseconds.
 */
internal class SerializedSqlDriver(private val delegate: SqlDriver) : SqlDriver by delegate {

    private val lock = ReentrantLock()

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = locked { delegate.execute(identifier, sql, parameters, binders) }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = locked { delegate.executeQuery(identifier, sql, mapper, parameters, binders) }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        // Statements only hold the lock for their own duration, so "this thread already holds it"
        // means it is inside a transaction: a nested one adds no hold (its hooks are folded into
        // the outermost transaction's, which would make a second unlock unreliable).
        if (lock.isHeldByCurrentThread) return delegate.newTransaction()
        lock.lock()
        try {
            val tx = delegate.newTransaction().value
            // Released exactly once, by the thread that took it (SQLDelight ends a transaction on
            // the thread that began it): at the outermost commit or rollback.
            tx.afterCommit { lock.unlock() }
            tx.afterRollback { lock.unlock() }
            return QueryResult.Value(tx)
        } catch (t: Throwable) {
            lock.unlock()
            throw t
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
