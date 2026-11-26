package dev.fonebrew.domain.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BudgetInputTest {
    @Test fun `major units become the ledger's minor unit`() {
        assertEquals(250L, BudgetInput.majorToMinor("2.50", "USD"))
        assertEquals(200L, BudgetInput.majorToMinor("2", "USD"))
        assertEquals(250L, BudgetInput.majorToMinor("2,5", "USD"))
        assertEquals(500L, BudgetInput.majorToMinor("500", "JPY"))   // 0 fraction digits: minor == major
        assertEquals(5L, BudgetInput.majorToMinor("0.05", "usd"))
    }

    @Test fun `bad input is null, never rounded or guessed`() {
        for (t in listOf("", " ", "abc", "1.2.3", "-1", "2.555", "1e3", "$5")) assertNull("'$t'", BudgetInput.majorToMinor(t, "USD"))
        assertNull(BudgetInput.majorToMinor("2.5", "JPY"))            // JPY has no decimals
        assertNull(BudgetInput.majorToMinor("99999999999999999999", "USD"))
    }
}
