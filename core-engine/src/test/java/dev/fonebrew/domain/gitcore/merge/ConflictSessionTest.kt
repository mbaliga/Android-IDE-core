package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*
import dev.fonebrew.domain.gitcore.textmerge.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConflictSessionTest {
    private fun expectSession(kind: SessionErrorKind, block: () -> Unit) {
        try { block(); fail("expected $kind") } catch (e: SessionException) { assertEquals(e.message, kind, e.kind) }
    }

    private val base = "h\n1\n2\n3\n4\n5\n6\n7\n8\n9\n10\nt\n"
    private fun twoRegionRepo(): TestRepo {
        val t = TestRepo()
        t.commitFiles("base", "f" to base, "g" to "a\nb\nc\n", "d" to "gone?\n", "k" to "k\n")
        t.branchFrom("topic")
        t.commitFiles("ours", "f" to base.replace("\n2\n", "\nO2\n").replace("\n9\n", "\nO9\n"), "g" to "a\nOURS\nc\n", "d" to "changed\n")
        t.on("topic") { t.commitFiles("theirs", "f" to base.replace("\n2\n", "\nT2\n").replace("\n9\n", "\nT9\n"), "g" to "a\nTHEIRS\nc\n", "d" to null) }
        t.repo.checkout("main")
        t.repo.merge("topic")
        return t
    }

    @Test fun `lists conflicted files with counts glyph status and navigation`() {
        val t = twoRegionRepo()
        val s = ConflictSession.open(t.repo)
        assertEquals(listOf("d", "f", "g"), s.files.map { it.path })
        val f = s.file("f")
        assertEquals("UU", f.code); assertEquals(2, f.regionCount); assertEquals(0, f.resolvedRegions)
        assertEquals(FileResolution.UNRESOLVED, f.resolution)
        assertTrue(f.resolution.glyph.isNotEmpty() && f.resolution.label.isNotEmpty())
        assertEquals("UD", s.file("d").code)
        assertFalse(s.file("d").textual)
        assertEquals(Position("f", 0), s.next())
        assertEquals(Position("f", 1), s.next(Position("f", 0)))
        assertEquals(Position("g", 0), s.next(Position("f", 1)))
        assertNull(s.next(Position("g", 0)))
        assertEquals(Position("f", 1), s.previous(Position("g", 0)))
        assertEquals("HEAD", s.file("f").oursLabel); assertEquals("topic", s.file("f").theirsLabel)
        val r = s.regions("f")
        assertEquals("O2\n", r[0].oursText); assertEquals("T2\n", r[0].theirsText); assertNull(r[0].baseText)
    }

    @Test fun `choose region by region preview and mark resolved writes and stages`() {
        val t = twoRegionRepo()
        val s = ConflictSession.open(t.repo)
        s.choose("f", 0, Choice.Ours)
        assertEquals(FileResolution.PARTIAL, s.file("f").resolution)
        assertEquals(1, s.file("f").resolvedRegions)
        assertTrue(s.preview("f").contains("<<<<<<<"))                 // region 1 still open
        expectSession(SessionErrorKind.UNRESOLVED_REGIONS) { s.markResolved("f") }
        assertEquals(Position("f", 1), s.next())
        s.choose("f", 1, Choice.Both(oursFirst = false))
        assertEquals(FileResolution.READY, s.file("f").resolution)
        s.markResolved("f")
        assertEquals(FileResolution.RESOLVED, s.file("f").resolution)
        assertEquals(base.replace("\n2\n", "\nO2\n").replace("\n9\n", "\nT9\nO9\n"), t.read("f"))
        assertNotNull(t.repo.readIndex().get("f", 0))
        assertEquals(listOf("d", "g"), t.repo.readIndex().conflictedPaths())
        assertFalse(s.isComplete); assertEquals(2, s.unresolvedCount)
        // whole-file choices for the non text conflict (theirs deleted it) and the last text file
        s.resolveWith("d", WholeFile.THEIRS)
        assertFalse(t.file("d").exists())
        s.chooseAll("g", Choice.Theirs); s.markResolved("g")
        assertTrue(s.isComplete)
        assertTrue(t.repo.readIndex().conflictedPaths().isEmpty())
        // and the merge can now be finished
        val id = t.repo.continueMerge()
        assertEquals(2, t.repo.db.readCommit(id).parents.size)
        assertEquals("a\nTHEIRS\nc\n", t.read("g"))
    }

    @Test fun `errors are typed`() {
        val t = twoRegionRepo()
        val s = ConflictSession.open(t.repo)
        expectSession(SessionErrorKind.UNKNOWN_PATH) { s.file("nope"); s.choose("nope", 0, Choice.Ours) }
        expectSession(SessionErrorKind.REGION_OUT_OF_RANGE) { s.choose("f", 9, Choice.Ours) }
        expectSession(SessionErrorKind.NOT_A_TEXT_CONFLICT) { s.choose("d", 0, Choice.Ours) }
        expectSession(SessionErrorKind.BASE_UNAVAILABLE) { s.choose("f", 0, Choice.Base) }   // merge-style markers carry no base
        expectSession(SessionErrorKind.NOT_A_TEXT_CONFLICT) { s.markResolved("d") }
    }

    @Test fun `custom text that still contains markers is refused`() {
        val t = twoRegionRepo()
        val s = ConflictSession.open(t.repo)
        s.chooseAll("g", Choice.Custom.ofText("<<<<<<< oops\nx\n=======\ny\n>>>>>>> oops\n"))
        expectSession(SessionErrorKind.MARKERS_REMAIN) { s.markResolved("g") }
        assertTrue(t.read("g").contains("OURS"))                              // nothing was written
    }

    @Test fun `a file edited outside re-enters the session through reload and malformed markers are reported`() {
        val t = twoRegionRepo()
        val s = ConflictSession.open(t.repo)
        // an agent resolves region 0 in the file by hand, leaving region 1 as markers
        val txt = t.read("f")
        val firstEnd = txt.indexOf(">>>>>>> topic\n") + ">>>>>>> topic\n".length
        val handFixed = "h\n1\nBY HAND\n3\n4\n5\n6\n7\n8\n" + txt.substring(txt.indexOf("<<<<<<<", firstEnd)).let { it.substring(0, it.indexOf(">>>>>>> topic\n") + ">>>>>>> topic\n".length) } + "10\nt\n"
        t.write("f", handFixed)
        s.reload("f")
        assertEquals(1, s.file("f").regionCount)
        // now break the structure
        t.write("f", "<<<<<<< HEAD\nx\n<<<<<<< again\ny\n=======\nz\n>>>>>>> topic\n")
        s.reload("f")
        assertEquals(FileResolution.UNREADABLE, s.file("f").resolution)
        assertFalse(s.file("f").textual)
        expectSession(SessionErrorKind.NOT_A_TEXT_CONFLICT) { s.choose("f", 0, Choice.Ours) }
        s.resolveWith("f", WholeFile.OURS)                                    // the escape hatch works
        assertEquals(FileResolution.RESOLVED, s.file("f").resolution)
        assertTrue(t.read("f").contains("O9"))
    }

    @Test fun `a hand resolved file with no markers is ready to stage`() {
        val t = twoRegionRepo()
        val s = ConflictSession.open(t.repo)
        t.write("g", "a\nhand\nc\n")
        s.reload("g")
        assertEquals(0, s.file("g").regionCount)
        assertEquals(FileResolution.READY, s.file("g").resolution)
        s.markResolved("g")
        assertEquals("a\nhand\nc\n", t.read("g"))
        assertNotNull(t.repo.readIndex().get("g", 0))
    }

    @Test fun `diff3 style markers expose the base choice`() {
        val t = TestRepo()
        t.repo.config.set("merge", null, "conflictstyle", "diff3"); t.repo.saveConfig()
        t.commitFiles("base", "g" to "a\nb\nc\n")
        t.branchFrom("topic")
        t.commitFiles("ours", "g" to "a\nO\nc\n")
        t.on("topic") { t.commitFiles("theirs", "g" to "a\nT\nc\n") }
        t.repo.checkout("main")
        t.repo.merge("topic")
        assertTrue(t.read("g").contains("||||||| "))
        val s = ConflictSession.open(t.repo)
        assertEquals("b\n", s.regions("g")[0].baseText)
        s.choose("g", 0, Choice.Base)
        s.markResolved("g")
        assertEquals("a\nb\nc\n", t.read("g"))
    }
}
