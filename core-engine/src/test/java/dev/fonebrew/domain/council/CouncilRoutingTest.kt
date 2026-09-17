package dev.fonebrew.domain.council

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CouncilRoutingTest {

    private val names = listOf("Skeptic", "Optimist", "Coder")

    @Test
    fun `leading mention resolves to the matching participant`() {
        assertEquals("Skeptic", CouncilRouting.addressee("@Skeptic what's the catch here?", names))
    }

    @Test
    fun `match is case-insensitive`() {
        assertEquals("Coder", CouncilRouting.addressee("@coder fix the null check", names))
    }

    @Test
    fun `leading whitespace before the sigil is tolerated`() {
        assertEquals("Optimist", CouncilRouting.addressee("  @Optimist go on", names))
    }

    @Test
    fun `mid-message mention is prose, not routing`() {
        assertNull(CouncilRouting.addressee("ask @Skeptic later, for now just summarize", names))
    }

    @Test
    fun `mention of a non-participant falls back to no routing`() {
        assertNull(CouncilRouting.addressee("@Bob are you there", names))
    }

    @Test
    fun `trailing punctuation right after the name is stripped before matching`() {
        assertEquals("Skeptic", CouncilRouting.addressee("@Skeptic, thoughts?", names))
        assertEquals("Skeptic", CouncilRouting.addressee("@Skeptic: go", names))
        assertEquals("Skeptic", CouncilRouting.addressee("@Skeptic!", names))
    }

    @Test
    fun `bare sigil with no token is not an address`() {
        assertNull(CouncilRouting.addressee("@ hello", names))
    }

    @Test
    fun `no sigil at all`() {
        assertNull(CouncilRouting.addressee("hey team, thoughts?", names))
    }
}
