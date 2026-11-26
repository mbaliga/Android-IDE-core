package dev.fonebrew.domain.gitcore.textmerge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class TextMergeTest {
    private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun m(base: String, ours: String, theirs: String, o: TextMergeOptions = TextMergeOptions()) = TextMerge.merge(b(base), b(ours), b(theirs), o)
    private fun text(r: TextMergeResult) = String(r.render(), Charsets.ISO_8859_1)

    @Test fun `non overlapping edits merge cleanly`() {
        val r = m("a\nb\nc\nd\ne\n", "A\nb\nc\nd\ne\n", "a\nb\nc\nd\nE\n")
        assertTrue(r.isClean); assertEquals("A\nb\nc\nd\nE\n", text(r))
    }

    @Test fun `identical edits on both sides are not a conflict`() {
        val r = m("a\nb\nc\n", "a\nX\nc\n", "a\nX\nc\n")
        assertTrue(r.isClean); assertEquals("a\nX\nc\n", text(r))
    }

    @Test fun `identical deletions on both sides drop the line once`() {
        val r = m("a\nb\nc\n", "a\nc\n", "a\nc\n")
        assertEquals("a\nc\n", text(r))
    }

    @Test fun `adjacent edits conflict like git`() {
        val r = m("a\nb\nc\nd\n", "a\nB\nc\nd\n", "a\nb\nC\nd\n")
        assertEquals(1, r.conflictCount)
        assertEquals("a\n<<<<<<< ours\nB\nc\n=======\nb\nC\n>>>>>>> theirs\nd\n", text(r))
    }

    @Test fun `a delete against a modify keeps both versions in the conflict`() {
        val r = m("a\nb\nc\n", "a\nc\n", "a\nB\nc\n")
        val reg = r.regions.single()
        assertEquals(emptyList<String>(), reg.ours); assertEquals(listOf("B\n"), reg.theirs)
        assertEquals(listOf("b\n"), reg.base)
    }

    @Test fun `no line of either side is ever dropped silently`() {
        // property: every output line is either in a conflict region or present in ours or theirs
        val rnd = Random(42)
        repeat(400) {
            val base = List(2 + rnd.nextInt(8)) { "l${rnd.nextInt(5)}\n" }
            fun mut(src: List<String>): List<String> {
                val o = src.toMutableList()
                repeat(1 + rnd.nextInt(3)) {
                    when (rnd.nextInt(3)) {
                        0 -> if (o.isNotEmpty()) o[rnd.nextInt(o.size)] = "n${rnd.nextInt(4)}\n"
                        1 -> o.add(rnd.nextInt(o.size + 1), "i${rnd.nextInt(4)}\n")
                        else -> if (o.isNotEmpty()) o.removeAt(rnd.nextInt(o.size))
                    }
                }
                return o
            }
            val ours = mut(base); val theirs = mut(base)
            for (style in MarkerStyle.values()) {
                val r = TextMerge.mergeLines(base, ours, theirs, TextMergeOptions(style))
                // resolving every region to ours must equal "ours' changes over base" restricted check:
                // choosing OURS then THEIRS for each conflict must keep that side's lines of the region
                val ro = Resolver.apply(r.segments, r.regions.associate { it.index to Choice.Ours })
                val rt = Resolver.apply(r.segments, r.regions.associate { it.index to Choice.Theirs })
                val rb = Resolver.apply(r.segments, r.regions.associate { it.index to Choice.Both() })
                val oursSet = ours.toSet(); val theirsSet = theirs.toSet()
                for (line in RawText.splitLines(ro.raw)) assertTrue("ours-resolution invented a line", line in oursSet || line in theirsSet || line in base)
                // every line that ours added (not in base multiset) must appear when taking ours or both
                val addedByOurs = ours.filter { it !in base }
                val addedByTheirs = theirs.filter { it !in base }
                for (l in addedByOurs) assertTrue("ours' added line $l lost under OURS", l in RawText.splitLines(ro.raw))
                for (l in addedByTheirs) assertTrue("theirs' added line $l lost under THEIRS", l in RawText.splitLines(rt.raw))
                for (l in addedByOurs) assertTrue("ours' added line lost under BOTH", l in RawText.splitLines(rb.raw))
                for (l in addedByTheirs) assertTrue("theirs' added line lost under BOTH", l in RawText.splitLines(rb.raw))
                if (r.isClean) {
                    val clean = String(r.render(), Charsets.ISO_8859_1)
                    for (l in addedByOurs) assertTrue("clean merge lost ours' line", l in RawText.splitLines(clean))
                    for (l in addedByTheirs) assertTrue("clean merge lost theirs' line", l in RawText.splitLines(clean))
                }
            }
        }
    }

    @Test fun `trivial cases short circuit`() {
        assertEquals("T\n", text(m("b\n", "b\n", "T\n")))
        assertEquals("O\n", text(m("b\n", "O\n", "b\n")))
        assertEquals("", text(m("", "", "")))
        assertTrue(m("", "x\n", "x\n").isClean)
    }

    @Test fun `diff3 and zdiff3 styles show the base`() {
        val d3 = m("a\nb\nc\n", "a\nX\nc\n", "a\nY\nc\n", TextMergeOptions(MarkerStyle.DIFF3))
        assertEquals("a\n<<<<<<< ours\nX\n||||||| base\nb\n=======\nY\n>>>>>>> theirs\nc\n", text(d3))
        val z = m("a\nb\nc\n", "a\nq\nX\nr\nc\n", "a\nq\nY\nr\nc\n", TextMergeOptions(MarkerStyle.ZDIFF3))
        assertEquals("a\nq\n<<<<<<< ours\nX\n||||||| base\nb\n=======\nY\n>>>>>>> theirs\nr\nc\n", text(z))
    }

    @Test fun `merge style splits where the sides agree and rejoins close conflicts`() {
        val split = m("a\nb\nc\n", "a\nq\nX\nr\nc\n", "a\nq\nY\nr\nc\n")
        assertEquals("a\nq\n<<<<<<< ours\nX\n=======\nY\n>>>>>>> theirs\nr\nc\n", text(split))
        // a common run of three or fewer lines between conflicts is absorbed
        val joined = m("a\nb\nc\n", "a\nX\nm1\nm2\nm3\nY\nc\n", "a\nZ\nm1\nm2\nm3\nW\nc\n")
        assertEquals(1, joined.conflictCount)
        val apart = m("a\nb\nc\n", "a\nX\nm1\nm2\nm3\nm4\nY\nc\n", "a\nZ\nm1\nm2\nm3\nm4\nW\nc\n")
        assertEquals(2, apart.conflictCount)
    }

    @Test fun `labels and marker size are honoured`() {
        val r = m("a\n", "b\n", "c\n", TextMergeOptions(oursLabel = "HEAD", theirsLabel = "feature", markerSize = 9))
        assertEquals("<<<<<<<<< HEAD\nb\n=========\nc\n>>>>>>>>> feature\n", text(r))
    }

    @Test fun `crlf files get crlf markers and a missing final newline is handled exactly`() {
        val crlf = m("a\r\nb\r\n", "a\r\nX\r\n", "a\r\nY\r\n")
        assertEquals("a\r\n<<<<<<< ours\r\nX\r\n=======\r\nY\r\n>>>>>>> theirs\r\n", text(crlf))
        val noeol = m("a\nb", "a\nX", "a\nY")
        assertEquals("a\n<<<<<<< ours\nX\n=======\nY\n>>>>>>> theirs\n", text(noeol))
        // a clean merge keeps a missing final newline
        assertEquals("a\nB", text(m("a\nb", "a\nB", "a\nb")))
        assertEquals("A\nb", text(m("a\nb", "A\nb", "a\nb")))
    }

    @Test fun `binary and oversized inputs are unmergeable and never rendered`() {
        val bin = byteArrayOf(1, 0, 2)
        val r = TextMerge.merge(b("a\n"), bin, b("a\n"))
        assertTrue(r.isUnmergeable); assertEquals(UnmergeableReason.BINARY, r.unmergeable!!.reason)
        try { r.render(); fail("rendered an unmergeable result") } catch (_: IllegalStateException) {}
        val big = TextMerge.merge(b("a\n"), b("b\n"), b("c\n"), TextMergeOptions(maxInputBytes = 1))
        assertEquals(UnmergeableReason.TOO_LARGE, big.unmergeable!!.reason)
    }

    @Test fun `non utf8 bytes survive a merge byte for byte`() {
        val latin = byteArrayOf('a'.code.toByte(), 0xE9.toByte(), '\n'.code.toByte())
        val r = TextMerge.merge(latin, latin, b("aÿ\n"))
        assertTrue(r.isClean)
        assertArrayEquals(b("aÿ\n"), r.render())
    }

    @Test fun `huge dissimilar inputs complete via the anchored fallback and lose nothing`() {
        val base = List(6000) { "line $it\n" }
        val ours = base.mapIndexed { i, l -> if (i % 2 == 0) "o$i\n" else l }
        val theirs = base.mapIndexed { i, l -> if (i % 2 == 1) "t$i\n" else l }
        val r = TextMerge.mergeLines(base, ours, theirs)
        val all = Resolver.apply(r.segments, r.regions.associate { it.index to Choice.Both() }).raw
        for (i in 0 until 6000 step 997) { assertTrue(if (i % 2 == 0) "o$i\n" in all else "t$i\n" in all) }
    }

    // -------------------------------------------------------------- markers

    @Test fun `parser reads merge and diff3 markers back`() {
        val raw = "x\n<<<<<<< HEAD\no1\n||||||| base\nb1\n=======\nt1\n>>>>>>> topic\ny\n"
        val p = ConflictMarkers.parse(raw)
        val reg = p.regions.single()
        assertEquals(listOf("o1\n"), reg.ours); assertEquals(listOf("b1\n"), reg.base); assertEquals(listOf("t1\n"), reg.theirs)
        assertEquals("HEAD", p.oursLabel); assertEquals("topic", p.theirsLabel); assertEquals("base", p.baseLabel)
        val back = MarkerRenderer.render(p.segments, MarkerStyle.DIFF3, 7, "HEAD", "base", "topic")
        assertEquals(raw, back)
        assertNull(ConflictMarkers.parse("<<<<<<< a\nx\n=======\ny\n>>>>>>> b\n").regions.single().base)
    }

    @Test fun `parser refuses malformed markers with a typed error`() {
        fun kind(s: String): MarkerErrorKind = try { ConflictMarkers.parse(s); fail("parsed: $s"); throw IllegalStateException() } catch (e: MarkerParseError) { e.kind }
        assertEquals(MarkerErrorKind.NESTED_START, kind("<<<<<<< a\n<<<<<<< b\nx\n=======\ny\n>>>>>>> b\n>>>>>>> a\n"))
        assertEquals(MarkerErrorKind.UNTERMINATED, kind("<<<<<<< a\nx\n=======\ny\n"))
        assertEquals(MarkerErrorKind.UNTERMINATED, kind("<<<<<<< a\nx\n"))
        assertEquals(MarkerErrorKind.STRAY_MARKER, kind("x\n>>>>>>> b\n"))
        assertEquals(MarkerErrorKind.STRAY_MARKER, kind("x\n||||||| base\n"))
        assertEquals(MarkerErrorKind.OUT_OF_ORDER, kind("<<<<<<< a\nx\n>>>>>>> b\n"))
        assertEquals(MarkerErrorKind.OUT_OF_ORDER, kind("<<<<<<< a\nx\n=======\ny\n=======\nz\n>>>>>>> b\n"))
        assertEquals(MarkerErrorKind.OUT_OF_ORDER, kind("<<<<<<< a\nx\n=======\ny\n||||||| base\n>>>>>>> b\n"))
        assertEquals(MarkerErrorKind.OUT_OF_ORDER, kind("<<<<<<< a\nx\n||||||| base\n||||||| again\n=======\ny\n>>>>>>> b\n"))
    }

    @Test fun `a lone separator outside a conflict is ordinary text`() {
        val p = ConflictMarkers.parse("Title\n=======\nbody\n")
        assertEquals(0, p.regions.size)
        assertTrue(Resolver.leftoverMarkers("Title\n=======\nbody\n").isEmpty())
        // longer or shorter runs are not markers of size 7
        assertTrue(ConflictMarkers.parse("<<<<<<<< a\n<<<<<<\n").regions.isEmpty())
    }

    @Test fun `a larger marker size is read when asked`() {
        val raw = "<<<<<<<<< a\nx\n=========\ny\n>>>>>>>>> b\n"
        assertEquals(1, ConflictMarkers.parse(raw, 9).regions.size)
        assertTrue(ConflictMarkers.parse(raw, 7).regions.isEmpty())
    }

    // -------------------------------------------------------------- resolution

    @Test fun `every choice yields the expected text and the validator sees leftovers`() {
        val r = m("a\nb\nc\n", "a\nX\nc\n", "a\nY\nc\n", TextMergeOptions(MarkerStyle.DIFF3))
        fun res(c: Choice) = Resolver.apply(r.segments, mapOf(0 to c)).raw
        assertEquals("a\nX\nc\n", res(Choice.Ours))
        assertEquals("a\nY\nc\n", res(Choice.Theirs))
        assertEquals("a\nb\nc\n", res(Choice.Base))
        assertEquals("a\nX\nY\nc\n", res(Choice.Both()))
        assertEquals("a\nY\nX\nc\n", res(Choice.Both(oursFirst = false)))
        assertEquals("a\nmine\nc\n", res(Choice.Custom.ofText("mine\n")))
        assertEquals("a\nmine\nc\n", res(Choice.Custom.ofText("mine")))   // newline added because more text follows
        val none = Resolver.apply(r.segments, emptyMap(), MarkerStyle.DIFF3)
        assertEquals(listOf(0), none.unresolved)
        assertTrue(Resolver.leftoverMarkers(res(Choice.Ours)).isEmpty())
        assertEquals(setOf('<', '|', '=', '>'), Resolver.leftoverMarkers(none.raw).map { it.marker }.toSet())
    }

    @Test fun `base is refused where unknown and unknown regions are refused`() {
        val r = m("a\nb\nc\n", "a\nX\nc\n", "a\nY\nc\n")   // merge style: base not recorded for refined regions? (single region keeps base)
        val parsed = ConflictMarkers.parse("<<<<<<< a\nx\n=======\ny\n>>>>>>> b\n")
        try { Resolver.apply(parsed.segments, mapOf(0 to Choice.Base)); fail() } catch (e: ResolutionException) { assertEquals(ResolutionErrorKind.BASE_UNAVAILABLE, e.kind) }
        try { Resolver.apply(r.segments, mapOf(9 to Choice.Ours)); fail() } catch (e: ResolutionException) { assertEquals(ResolutionErrorKind.UNKNOWN_REGION, e.kind) }
    }

    @Test fun `a partly resolved file re-enters the parser with only the open regions`() {
        val r = m("a\nb\nc\nc2\nc3\nc4\nd\ne\n", "a\nB\nc\nc2\nc3\nc4\nD\ne\n", "a\nBB\nc\nc2\nc3\nc4\nDD\ne\n")
        assertEquals(2, r.conflictCount)
        val half = Resolver.apply(r.segments, mapOf(0 to Choice.Theirs))
        assertEquals(listOf(1), half.unresolved)
        val again = ConflictMarkers.parse(half.raw)
        assertEquals(1, again.regions.size)
        assertEquals(listOf("D\n"), again.regions[0].ours)
    }
}
