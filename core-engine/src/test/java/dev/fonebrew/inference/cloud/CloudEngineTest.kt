package dev.fonebrew.inference.cloud

import dev.fonebrew.domain.cloud.Source
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [mergeSourcesByUrl], the dedup step [CloudEngine.generate] runs every time a
 * subclass's [CloudEngine.sourcesOf] override reports sources for one SSE event (W2 audit
 * fix: a provider re-reporting the same source across multiple chunks — e.g. Gemini's
 * `groundingMetadata`, documented as arriving cumulatively — must not duplicate rows in
 * [CloudEngine.lastSources] / the sources footer).
 */
class CloudEngineTest {

    @Test
    fun `first event's sources are all kept`() {
        val sources = mutableListOf<Source>()
        mergeSourcesByUrl(sources, listOf(Source("A", "https://a.example"), Source("B", "https://b.example")))
        assertEquals(listOf(Source("A", "https://a.example"), Source("B", "https://b.example")), sources)
    }

    @Test
    fun `a later event repeating the same url is dropped, not duplicated`() {
        val sources = mutableListOf<Source>()
        mergeSourcesByUrl(sources, listOf(Source("A", "https://a.example")))
        mergeSourcesByUrl(sources, listOf(Source("A", "https://a.example"), Source("B", "https://b.example")))
        assertEquals(listOf(Source("A", "https://a.example"), Source("B", "https://b.example")), sources)
    }

    @Test
    fun `a later event with only new urls appends onto the existing list`() {
        val sources = mutableListOf<Source>()
        mergeSourcesByUrl(sources, listOf(Source("A", "https://a.example")))
        mergeSourcesByUrl(sources, listOf(Source("C", "https://c.example")))
        assertEquals(
            listOf(Source("A", "https://a.example"), Source("C", "https://c.example")),
            sources,
        )
    }

    @Test
    fun `repeated url with a different title still counts as a duplicate`() {
        // Dedup key is the url, matching what the sources footer actually renders as the
        // tappable identity — a provider re-sending the same result with slightly different
        // title casing/whitespace across chunks must not produce two rows.
        val sources = mutableListOf<Source>()
        mergeSourcesByUrl(sources, listOf(Source("A", "https://a.example")))
        mergeSourcesByUrl(sources, listOf(Source("A (updated)", "https://a.example")))
        assertEquals(listOf(Source("A", "https://a.example")), sources)
    }
}
