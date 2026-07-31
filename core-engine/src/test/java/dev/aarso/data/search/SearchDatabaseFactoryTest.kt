package dev.aarso.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP3's driver spike, made permanent: proves `androidx.sqlite:sqlite-bundled` actually opens a
 * real SQLite connection and runs a real FTS5 `MATCH`/`bm25()` query **on the JVM**, without a
 * device/emulator/Robolectric. This was the biggest architectural risk in
 * `FONEBREW_SEARCH_SPEC.md` D1 for this repo (no device, ever — CLAUDE.md's environment-honesty
 * rule) and it resolves cleanly: the KMP driver artifact ships JVM-host native binaries.
 *
 * Toolchain note for future readers: getting here required pinning `sqldelight` to 2.1.0 (not
 * the spec's originally-cited 2.3.2) and `com.eygraber:sqldelight-androidx-driver` to 0.0.1 —
 * both verified empirically against this project's Kotlin 2.1.0 pin; newer releases of either
 * pull a `kotlin-gradle-plugin`/`kotlin-stdlib` metadata version this project's compiler can't
 * read. See the WP3 commit message and the comments in `gradle/libs.versions.toml`.
 */
class SearchDatabaseFactoryTest {

    @Test fun `in-memory database opens and reports zero rows`() {
        val handle = SearchDriverFactory.createInMemory()
        val db = handle.database
        assertEquals(0L, db.searchQueries.countProjections().executeAsOne())
    }

    @Test fun `fts5 match finds an inserted row via bm25 candidate ordering`() {
        val handle = SearchDriverFactory.createInMemory()
        val db = handle.database
        db.searchQueries.upsertProjection(
            conv_id = "c1",
            title = "gradle build cache misses",
            snippet = "configuration cache invalidated every run",
            body = "gradle build cache misses configuration cache invalidated every run",
            title_raw = "Gradle build cache misses",
            snippet_raw = "configuration cache invalidated every run",
            body_raw = "Gradle build cache misses. Configuration cache invalidated every run.",
            updated_at = 1_735_689_600_000L,
            created_at = 1_735_689_600_000L,
            projection_version = 1L,
        )
        db.searchQueries.upsertProjection(
            conv_id = "c2",
            title = "unrelated conversation about weather",
            snippet = "sunny today",
            body = "unrelated conversation about weather sunny today",
            title_raw = "Unrelated conversation about weather",
            snippet_raw = "sunny today",
            body_raw = "Unrelated conversation about weather. Sunny today.",
            updated_at = 1_735_689_600_000L,
            created_at = 1_735_689_600_000L,
            projection_version = 1L,
        )

        val hits = db.searchQueries.searchCandidates(ftsQuery = "cache", limit = 10).executeAsList()
        assertEquals(1, hits.size)
        assertEquals("c1", hits[0].conv_id)
    }

    @Test fun `fts5 phrase query requires detail=full to work`() {
        // Table-stakes per spec §6.1 — proves detail='full' actually took effect, not just
        // that unquoted MATCH works (which would also pass under detail='column').
        val handle = SearchDriverFactory.createInMemory()
        val db = handle.database
        db.searchQueries.upsertProjection(
            conv_id = "c1",
            title = "gradle build cache",
            snippet = "s",
            body = "the build cache was invalidated",
            title_raw = "gradle build cache",
            snippet_raw = "s",
            body_raw = "the build cache was invalidated",
            updated_at = 0L,
            created_at = 0L,
            projection_version = 1L,
        )

        val phraseHit = db.searchQueries.searchCandidates(ftsQuery = "\"build cache\"", limit = 10).executeAsList()
        assertEquals(1, phraseHit.size)

        val wrongOrderMiss = db.searchQueries.searchCandidates(ftsQuery = "\"cache build\"", limit = 10).executeAsList()
        assertTrue(wrongOrderMiss.isEmpty())
    }

    @Test fun `index_state upsert round-trips and resumes from last_indexed_rowid`() {
        val handle = SearchDriverFactory.createInMemory()
        val db = handle.database
        assertEquals(null, db.searchQueries.selectIndexState().executeAsOneOrNull())

        db.searchQueries.upsertIndexState(
            last_indexed_rowid = 42L,
            projection_version = 1L,
            tokenizer_version = 1L,
            schema_version = 1L,
            updated_at = 100L,
        )
        val first = db.searchQueries.selectIndexState().executeAsOne()
        assertEquals(42L, first.last_indexed_rowid)

        db.searchQueries.upsertIndexState(
            last_indexed_rowid = 99L,
            projection_version = 1L,
            tokenizer_version = 1L,
            schema_version = 1L,
            updated_at = 200L,
        )
        val second = db.searchQueries.selectIndexState().executeAsOne()
        assertEquals(99L, second.last_indexed_rowid)
    }
}
