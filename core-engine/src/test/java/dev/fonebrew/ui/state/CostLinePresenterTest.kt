package dev.fonebrew.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CostLinePresenterTest {

    private val meta = TurnCostMetadata(costMinor = 1234L, tokensIn = 500L, tokensOut = 250L)

    // ---- the exhaustive 2x2x2 truth table (toggle x provenance x metadata presence) --------

    @Test fun `toggle ON, CLOUD, metadata present -- the only case that renders a line`() {
        val line = CostLinePresenter.resolve(
            showPerTurnCost = true,
            provenance = TurnProvenance.CLOUD,
            metadata = meta,
            currencyCode = "USD",
            locale = Locale.US,
        )
        assertEquals("≈ $12.34 · in 500 / out 250 tok", line)
    }

    @Test fun `toggle ON, CLOUD, metadata absent -- never estimate at render time`() {
        assertNull(
            CostLinePresenter.resolve(
                showPerTurnCost = true,
                provenance = TurnProvenance.CLOUD,
                metadata = null,
                currencyCode = "USD",
                locale = Locale.US,
            ),
        )
    }

    @Test fun `toggle ON, ON_DEVICE, metadata present -- on-device costs nothing, no line even if metadata leaked in`() {
        assertNull(
            CostLinePresenter.resolve(
                showPerTurnCost = true,
                provenance = TurnProvenance.ON_DEVICE,
                metadata = meta,
                currencyCode = "USD",
                locale = Locale.US,
            ),
        )
    }

    @Test fun `toggle ON, ON_DEVICE, metadata absent -- the ordinary on-device case, no line`() {
        assertNull(
            CostLinePresenter.resolve(
                showPerTurnCost = true,
                provenance = TurnProvenance.ON_DEVICE,
                metadata = null,
                currencyCode = "USD",
                locale = Locale.US,
            ),
        )
    }

    @Test fun `toggle OFF, CLOUD, metadata present -- the owner's default-OFF ask wins over everything else`() {
        assertNull(
            CostLinePresenter.resolve(
                showPerTurnCost = false,
                provenance = TurnProvenance.CLOUD,
                metadata = meta,
                currencyCode = "USD",
                locale = Locale.US,
            ),
        )
    }

    @Test fun `toggle OFF, CLOUD, metadata absent`() {
        assertNull(
            CostLinePresenter.resolve(
                showPerTurnCost = false,
                provenance = TurnProvenance.CLOUD,
                metadata = null,
                currencyCode = "USD",
                locale = Locale.US,
            ),
        )
    }

    @Test fun `toggle OFF, ON_DEVICE, metadata present`() {
        assertNull(
            CostLinePresenter.resolve(
                showPerTurnCost = false,
                provenance = TurnProvenance.ON_DEVICE,
                metadata = meta,
                currencyCode = "USD",
                locale = Locale.US,
            ),
        )
    }

    @Test fun `toggle OFF, ON_DEVICE, metadata absent -- everything off, still nothing`() {
        assertNull(
            CostLinePresenter.resolve(
                showPerTurnCost = false,
                provenance = TurnProvenance.ON_DEVICE,
                metadata = null,
                currencyCode = "USD",
                locale = Locale.US,
            ),
        )
    }

    // ---- formatting goes through the existing currency/locale preference, not a raw number -

    @Test fun `the amount is formatted via the display-currency preference, not a bare minor-unit integer`() {
        val line = CostLinePresenter.resolve(
            showPerTurnCost = true,
            provenance = TurnProvenance.CLOUD,
            metadata = TurnCostMetadata(costMinor = 5000L, tokensIn = 1000L, tokensOut = 1000L),
            currencyCode = "USD",
            locale = Locale.US,
        )
        assertEquals("≈ $50.00 · in 1,000 / out 1,000 tok", line)
    }

    @Test fun `a non-default display currency changes the label, same sovereignty stance (no exchange rate)`() {
        // Same numeric costMinor, INR display currency instead of USD: only the label changes,
        // matching LocaleFormatTest's own "INR minor units format under en-IN" case rather than
        // asserting an exact symbol under a non-Indian locale (JDK-dependent).
        val line = CostLinePresenter.resolve(
            showPerTurnCost = true,
            provenance = TurnProvenance.CLOUD,
            metadata = TurnCostMetadata(costMinor = 12_345_678L, tokensIn = 1L, tokensOut = 1L),
            currencyCode = "INR",
            locale = Locale.forLanguageTag("en-IN"),
        )
        requireNotNull(line)
        assertTrue("expected rupee sign in $line", line.contains("₹"))
        assertTrue("expected lakh grouping in $line", line.contains("1,23,456"))
        assertTrue("expected paise in $line", line.contains(".78"))
    }

    @Test fun `token counts go through locale grouping (Indian lakh grouping under en-IN)`() {
        val line = CostLinePresenter.resolve(
            showPerTurnCost = true,
            provenance = TurnProvenance.CLOUD,
            metadata = TurnCostMetadata(costMinor = 100L, tokensIn = 123456L, tokensOut = 1L),
            currencyCode = "USD",
            locale = Locale.forLanguageTag("en-IN"),
        )
        assertEquals("≈ $1.00 · in 1,23,456 / out 1 tok", line)
    }

    @Test fun `zero cost is a real, honestly-rendered number, not treated as absent`() {
        val line = CostLinePresenter.resolve(
            showPerTurnCost = true,
            provenance = TurnProvenance.CLOUD,
            metadata = TurnCostMetadata(costMinor = 0L, tokensIn = 10L, tokensOut = 5L),
            currencyCode = "USD",
            locale = Locale.US,
        )
        assertEquals("≈ $0.00 · in 10 / out 5 tok", line)
    }

    // ---- TurnCostMetadata.from — the raw metadata-map parse, all-or-nothing -----------------

    @Test fun `from parses three well-formed strings into a TurnCostMetadata`() {
        val parsed = TurnCostMetadata.from("1234", "500", "250")
        assertEquals(TurnCostMetadata(1234L, 500L, 250L), parsed)
    }

    @Test fun `from is null when costMinor is missing`() {
        assertNull(TurnCostMetadata.from(null, "500", "250"))
    }

    @Test fun `from is null when tokensIn is missing`() {
        assertNull(TurnCostMetadata.from("1234", null, "250"))
    }

    @Test fun `from is null when tokensOut is missing`() {
        assertNull(TurnCostMetadata.from("1234", "500", null))
    }

    @Test fun `from is null when all three are missing -- the ordinary on-device turn`() {
        assertNull(TurnCostMetadata.from(null, null, null))
    }

    @Test fun `from is null on a malformed number rather than silently coercing it`() {
        assertNull(TurnCostMetadata.from("not-a-number", "500", "250"))
        assertNull(TurnCostMetadata.from("1234", "not-a-number", "250"))
        assertNull(TurnCostMetadata.from("1234", "500", "not-a-number"))
    }

    @Test fun `from rejects a partially-corrupted record the same as a fully-absent one -- never a placeholder in one slot`() {
        // Two good fields and one garbage field must not render "in 500 / out ? tok" or similar —
        // the whole record is treated as absent (see resolve's "toggle ON, CLOUD, metadata absent" case).
        val partial = TurnCostMetadata.from("1234", "500", "")
        assertNull(partial)
        assertNull(
            CostLinePresenter.resolve(
                showPerTurnCost = true,
                provenance = TurnProvenance.CLOUD,
                metadata = partial,
                currencyCode = "USD",
                locale = Locale.US,
            ),
        )
    }
}
