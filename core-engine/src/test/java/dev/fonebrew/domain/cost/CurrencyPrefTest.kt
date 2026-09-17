package dev.fonebrew.domain.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrencyPrefTest {

    @Test fun `recognised ISO-4217 codes are valid, case and whitespace insensitive`() {
        assertTrue(CurrencyPref.isValid("USD"))
        assertTrue(CurrencyPref.isValid("inr"))
        assertTrue(CurrencyPref.isValid("  JPY  "))
    }

    @Test fun `garbage codes are not valid`() {
        assertFalse(CurrencyPref.isValid("XYZNOTREAL"))
        assertFalse(CurrencyPref.isValid(""))
        assertFalse(CurrencyPref.isValid("US"))
    }

    @Test fun `normalize uppercases a valid code`() {
        assertEquals("INR", CurrencyPref.normalize(" inr "))
        assertEquals("USD", CurrencyPref.normalize("usd"))
    }

    @Test fun `normalize falls back to the default for anything invalid — never a crashy label`() {
        assertEquals(CurrencyPref.DEFAULT, CurrencyPref.normalize("not a currency"))
        assertEquals(CurrencyPref.DEFAULT, CurrencyPref.normalize(""))
    }

    @Test fun `default is USD`() {
        assertEquals("USD", CurrencyPref.DEFAULT)
    }
}
