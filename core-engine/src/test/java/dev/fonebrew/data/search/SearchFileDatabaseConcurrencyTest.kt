package dev.fonebrew.data.search

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The production database is FILE-backed (SearchDriverFactory.create), which opens a pool of
 * reader connections; every other test here uses the in-memory single-connection mode. This
 * starts the same reactive reads SearchViewModel starts at construction, concurrently, against
 * a real file.
 */
class SearchFileDatabaseConcurrencyTest {

    @Test fun `first reactive reads on a fresh file database all succeed concurrently`() {
        repeat(20) { round ->
            val dir = File.createTempFile("search-db-$round-", "").apply { delete(); mkdirs() }
            try {
                val handle = SearchDriverFactory.createAt(File(dir, "search.db").absolutePath)
                val q = handle.database.searchQueries
                runBlocking {
                    withTimeout(30_000) {
                        val jobs = List(8) {
                            listOf(
                                async { q.selectSavedSearches().asFlow().mapToList(Dispatchers.IO).first() },
                                async { q.selectRecentSearches(10).asFlow().mapToList(Dispatchers.IO).first() },
                                async { q.selectDistinctProjects().asFlow().mapToList(Dispatchers.IO).first() },
                                async { q.selectDistinctModelIds().asFlow().mapToList(Dispatchers.IO).first() },
                            )
                        }.flatten()
                        jobs.awaitAll()
                    }
                }
                assertEquals(0, q.selectSavedSearches().executeAsList().size)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test fun `reads stay correct while another thread writes transactions to the same file`() {
        val dir = File.createTempFile("search-db-rw-", "").apply { delete(); mkdirs() }
        try {
            val handle = SearchDriverFactory.createAt(File(dir, "search.db").absolutePath)
            val q = handle.database.searchQueries
            runBlocking {
                withTimeout(60_000) {
                    val writer = async(Dispatchers.IO) {
                        repeat(200) { i ->
                            handle.database.transaction {
                                q.insertSavedSearch("id$i", "n$i", "q$i", 0L, i.toLong(), null)
                            }
                        }
                    }
                    val readers = List(6) {
                        async(Dispatchers.IO) {
                            repeat(200) { q.selectSavedSearches().executeAsList(); q.selectRecentSearches(5).executeAsList() }
                        }
                    }
                    (readers + writer).awaitAll()
                }
            }
            assertEquals(200, q.selectSavedSearches().executeAsList().size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
