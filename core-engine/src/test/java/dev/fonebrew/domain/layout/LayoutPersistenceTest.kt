package dev.fonebrew.domain.layout

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPersistenceTest {
    private val codec = LayoutCodec()

    @Test fun `every preset round trips through JSON`() {
        for (p in PaneLayoutPresets.all) {
            val back = codec.decode(codec.encode(p.layout))
            assertEquals(p.id.name, DecodeResult.Ok(p.layout).layout.panes(), (back as DecodeResult.Ok).layout.panes())
            assertEquals(p.layout.focused, back.layout.focused)
            assertEquals(p.layout.root.toString().length, back.layout.root.toString().length)
        }
    }

    @Test fun `maximised pane round trips`() {
        val l = (PaneLayoutPresets.desktopCoding.layout.maximise(PaneId(PaneKind.TREE)) as LayoutEdit.Applied).layout
        assertEquals(PaneId(PaneKind.TREE), (codec.decode(codec.encode(l)) as DecodeResult.Ok).layout.maximised)
    }

    private fun invalid(json: String?, allowed: Set<PaneKind> = PaneKind.entries.toSet()) =
        codec.decode(json, allowed) is DecodeResult.Invalid

    @Test fun `corrupt input never throws and is Invalid`() {
        val good = codec.encode(PaneLayoutPresets.desktopCoding.layout)
        val cases = listOf(
            null, "", "   ", "not json", "[]", "{}", "{\"v\":1}", good.dropLast(7), "{\"v\":\"x\"}",
            """{"v":1,"root":5,"focus":"EDITOR#1"}""",
            """{"v":1,"root":{"t":"leaf","pane":"EDITOR#1"},"focus":"TREE#1"}""",
            """{"v":1,"root":{"t":"leaf","pane":"NOPE#1"},"focus":"NOPE#1"}""",
            """{"v":1,"root":{"t":"leaf","pane":"EDITOR#0"},"focus":"EDITOR#0"}""",
            """{"v":1,"root":{"t":"blob"},"focus":"EDITOR#1"}""",
            """{"v":1,"root":{"t":"split","axis":"DIAG","children":[],"fractions":[]},"focus":"EDITOR#1"}""",
            """{"v":1,"root":{"t":"split","axis":"ROW","children":[{"t":"leaf","pane":"TREE#1"},{"t":"leaf","pane":"EDITOR#1"}],"fractions":[0.5]},"focus":"EDITOR#1"}""",
            """{"v":1,"root":{"t":"split","axis":"ROW","children":[{"t":"leaf","pane":"TREE#1"},{"t":"leaf","pane":"EDITOR#1"}],"fractions":[0.5,-1]},"focus":"EDITOR#1"}""",
            """{"v":1,"root":{"t":"split","axis":"ROW","children":[{"t":"leaf","pane":"TREE#1"},{"t":"leaf","pane":"EDITOR#1"}],"fractions":[0.5,0]},"focus":"EDITOR#1"}""",
            // duplicate pane
            """{"v":1,"root":{"t":"split","axis":"ROW","children":[{"t":"leaf","pane":"EDITOR#1"},{"t":"leaf","pane":"EDITOR#1"}],"fractions":[0.5,0.5]},"focus":"EDITOR#1"}""",
            // singleton kind twice
            """{"v":1,"root":{"t":"split","axis":"ROW","children":[{"t":"leaf","pane":"TREE#1"},{"t":"leaf","pane":"TREE#2"}],"fractions":[0.5,0.5]},"focus":"TREE#1"}""",
            // maximised pane that does not exist
            """{"v":1,"root":{"t":"leaf","pane":"EDITOR#1"},"focus":"EDITOR#1","maximised":"GIT#1"}""",
            // future version
            """{"v":99,"root":{"t":"leaf","pane":"EDITOR#1"},"focus":"EDITOR#1"}""",
            "x".repeat(LayoutCodec.MAX_CHARS + 1),
        )
        for (c in cases) assertTrue("should be invalid: ${c?.take(80)}", invalid(c))
    }

    @Test fun `absurd depth is rejected, not a stack overflow`() {
        var node = """{"t":"leaf","pane":"EDITOR#1"}"""
        repeat(5000) { node = """{"t":"split","axis":"ROW","children":[$node,{"t":"leaf","pane":"TREE#1"}],"fractions":[0.5,0.5]}""" }
        assertTrue(invalid("""{"v":1,"root":$node,"focus":"EDITOR#1"}"""))
    }

    @Test fun `positive fractions that do not sum to one are renormalised`() {
        val j = """{"v":1,"root":{"t":"split","axis":"ROW","children":[{"t":"leaf","pane":"TREE#1"},{"t":"leaf","pane":"EDITOR#1"}],"fractions":[1,3]},"focus":"EDITOR#1"}"""
        val l = (codec.decode(j) as DecodeResult.Ok).layout
        assertEquals(0.25f, (l.root as LayoutNode.Split).fractions[0], 1e-4f)
    }

    @Test fun `a pane kind outside the allowed set invalidates the layout`() {
        val json = codec.encode(PaneLayoutPresets.ultrawideCoding.layout)
        assertTrue(invalid(json, PaneKind.entries.toSet() - PaneKind.ASSISTANT))
        assertFalse(invalid(json))
    }

    @Test fun `migration chain upgrades an older document`() {
        val v0 = """{"v":0,"pane":"EDITOR#1"}"""
        val migrating = LayoutCodec(mapOf(0 to { o: JSONObject ->
            JSONObject().put("v", 1).put("root", JSONObject().put("t", "leaf").put("pane", o.getString("pane"))).put("focus", o.getString("pane"))
        }))
        val r = migrating.decode(v0) as DecodeResult.Ok
        assertEquals(listOf(PaneId(PaneKind.EDITOR)), r.layout.panes())
        assertTrue("no migration registered -> invalid, not a crash", codec.decode(v0) is DecodeResult.Invalid)
    }

    @Test fun `a throwing migration is contained`() {
        val bad = LayoutCodec(mapOf(0 to { _: JSONObject -> error("boom") }))
        assertTrue(bad.decode("""{"v":0}""") is DecodeResult.Invalid)
    }

    // ---- fingerprints / repository --------------------------------------------------------

    @Test fun `density buckets use nearest standard dpi`() {
        assertEquals(DensityBucket.MDPI, DensityBucket.of(160))
        assertEquals(DensityBucket.HDPI, DensityBucket.of(240))
        assertEquals(DensityBucket.XHDPI, DensityBucket.of(320))
        assertEquals(DensityBucket.XXHDPI, DensityBucket.of(420)) // typical flagship phone
        assertEquals(DensityBucket.XXHDPI, DensityBucket.of(480))
        assertEquals(DensityBucket.XXXHDPI, DensityBucket.of(640))
        assertEquals(DensityBucket.LDPI, DensityBucket.of(100))
    }

    private val handheld = DisplayFingerprint(0, ScreenClass.PHONE, DensityBucket.XXHDPI)
    private val docked = DisplayFingerprint(2, ScreenClass.ULTRAWIDE, DensityBucket.MDPI)

    @Test fun `fingerprint keys differ in each component`() {
        val keys = setOf(
            handheld.storageKey,
            handheld.copy(displayId = 1).storageKey,
            handheld.copy(screenClass = ScreenClass.TABLET).storageKey,
            handheld.copy(density = DensityBucket.HDPI).storageKey,
        )
        assertEquals(4, keys.size)
    }

    @Test fun `dock and undock do not clobber each other`() {
        val store = InMemoryLayoutStore()
        val repo = DockedLayoutRepository(store)
        val phone = PaneLayout(LayoutNode.leaf(PaneKind.SEARCH), PaneId(PaneKind.SEARCH))
        val dockedLayout = PaneLayoutPresets.ultrawideReview.layout
        repo.save(handheld, phone)
        repo.save(docked, dockedLayout)
        assertEquals(phone.panes(), repo.load(handheld).layout.panes())
        assertEquals(dockedLayout.panes(), repo.load(docked).layout.panes())
        assertEquals(LayoutSource.STORED, repo.load(docked).source)
        repo.reset(docked)
        assertEquals(phone.panes(), repo.load(handheld).layout.panes())
    }

    @Test fun `nothing stored gives the class preset`() {
        val l = DockedLayoutRepository(InMemoryLayoutStore()).load(docked)
        assertEquals(LayoutSource.PRESET, l.source)
        assertEquals(PaneLayoutPresets.ultrawideCoding.layout, l.layout)
        assertEquals(null, l.fallbackReason)
    }

    @Test fun `corrupt stored data falls back to the preset with a reason`() {
        val store = InMemoryLayoutStore().also { it.write(docked.storageKey, "{{{{") }
        val l = DockedLayoutRepository(store).load(docked)
        assertEquals(LayoutSource.PRESET, l.source)
        assertEquals(PaneLayoutPresets.ultrawideCoding.layout, l.layout)
        assertNotEquals(null, l.fallbackReason)
    }

    @Test fun `changed pane set falls back to the preset`() {
        val store = InMemoryLayoutStore()
        DockedLayoutRepository(store).save(docked, PaneLayoutPresets.ultrawideCoding.layout)
        val narrowed = DockedLayoutRepository(store, allowedKinds = PaneKind.entries.toSet() - PaneKind.RAIL)
        val l = narrowed.load(docked)
        assertEquals(LayoutSource.PRESET, l.source)
        assertEquals("pane set changed should be reported", true, l.fallbackReason != null)
    }
}
