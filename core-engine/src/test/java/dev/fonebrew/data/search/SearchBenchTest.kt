package dev.fonebrew.data.search

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * `SearchBench` (Doc `FONEBREW_SEARCH_SPEC.md` §12, WP15): synthetic-corpus timing over the
 * T0-candidate + [dev.fonebrew.domain.search.LexicalSearch] rescore path (WP7) — the only tier that
 * has meaning in M0-M3 (fused/vector paint budgets need M4's embeddings, out of scope here).
 *
 * **These are container/JVM numbers, not device numbers.** This container has no phone/emulator
 * (CLAUDE.md's environment-honesty rule) — the real p50/p95 targets from the spec are
 * owner-verified only. What these assertions actually guard is a *shape* regression: an
 * accidentally-quadratic query path, or indexing that stops scaling linearly — not a device-class
 * performance budget. Thresholds are deliberately loose for that reason.
 *
 * 1k and 10k run in the default gate (a synthetic in-memory corpus, a handful of seconds at
 * most). 100k is opt-in/manual only ([hundredKSyntheticCorpusManualOnly], `@Ignore`d) — the spec
 * itself flags it as possibly too slow for routine runs; un-`@Ignore` it locally to sample it.
 */
class SearchBenchTest {

    private val vocabulary = listOf(
        "gradle", "kotlin", "compose", "search", "index", "fts5", "sqlite", "android",
        "build", "cache", "model", "inference", "council", "loop", "graph", "device",
        "usb", "arduino", "git", "branch", "commit", "review", "diff", "token",
        "embedding", "cloud", "provider", "keystore", "session", "thread", "message",
    )

    private fun syntheticRow(i: Int, rng: Random): SearchProjector.Row {
        val wordCount = 40 + rng.nextInt(120)
        val body = (0 until wordCount).joinToString(" ") { vocabulary[rng.nextInt(vocabulary.size)] }
        val title = (0 until 3 + rng.nextInt(4)).joinToString(" ") { vocabulary[rng.nextInt(vocabulary.size)] }
        return SearchProjector.Row(
            convId = "conv-$i",
            title = title, snippet = body.take(80), body = body,
            titleRaw = title, snippetRaw = body.take(80), bodyRaw = body,
            updatedAt = i.toLong(), createdAt = i.toLong(),
            projectionVersion = SearchProjector.PROJECTION_VERSION,
            starred = i % 17 == 0, archived = false, projectId = null,
            modelIds = "", turnCount = (1 + rng.nextInt(30)).toLong(), branchCount = 0L,
            hasImage = false, hasCode = i % 5 == 0, costMinor = 0L, lastUsedAt = null,
        )
    }

    private fun runBench(size: Int) {
        val handle = SearchDriverFactory.createInMemory()
        val rng = Random(size) // seeded — deterministic across runs, not flaky-by-corpus-shape
        val rows = (0 until size).map { syntheticRow(it, rng) }

        val indexStart = System.nanoTime()
        SearchIndexer.reindex(handle.database, rows, nowMillis = 0L)
        val indexMs = (System.nanoTime() - indexStart) / 1_000_000

        val queries = listOf("gradle", "gradle kotlin", "search index cache")
        val timings = queries.associateWith { q ->
            val t0 = System.nanoTime()
            SearchQuery.search(handle.database, q, nowMillis = 0L)
            (System.nanoTime() - t0) / 1_000_000
        }

        println(
            "[SearchBench] size=$size index=${indexMs}ms " +
                timings.entries.joinToString(" ") { (q, ms) -> "\"$q\"=${ms}ms" } +
                " — container/JVM numbers, NOT device numbers (CLAUDE.md's environment-honesty rule).",
        )

        assertTrue("indexing $size synthetic docs took ${indexMs}ms — investigate for a regression", indexMs < size * 5L + 10_000)
        timings.forEach { (q, ms) -> assertTrue("search \"$q\" over $size docs took ${ms}ms", ms < 5_000) }
    }

    @Test fun `1k synthetic corpus`() = runBench(1_000)

    @Test fun `10k synthetic corpus`() = runBench(10_000)

    @Ignore("manual/opt-in only — see this class's KDoc; the spec itself flags 100k as possibly too slow for routine runs")
    @Test fun `100k synthetic corpus (manual)`() = runBench(100_000)
}
