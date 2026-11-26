package dev.fonebrew.domain.layout

import org.json.JSONArray
import org.json.JSONObject

/** Density buckets by nearest standard dpi (120/160/240/320/480/640); boundaries are midpoints. */
enum class DensityBucket {
    LDPI, MDPI, HDPI, XHDPI, XXHDPI, XXXHDPI;

    companion object {
        fun of(densityDpi: Int): DensityBucket = when {
            densityDpi < 140 -> LDPI
            densityDpi < 200 -> MDPI
            densityDpi < 280 -> HDPI
            densityDpi < 400 -> XHDPI
            densityDpi < 560 -> XXHDPI
            else -> XXXHDPI
        }
    }
}

/**
 * Identifies "this display in this situation". Docking changes the display id (extended) and/or
 * the size class and density (mirrored), so the docked layout and the handheld one live under
 * different keys and neither clobbers the other.
 */
data class DisplayFingerprint(val displayId: Int, val screenClass: ScreenClass, val density: DensityBucket) {
    val storageKey: String get() = "layout.v1.d$displayId.${screenClass.name}.${density.name}"
}

/** Tiny key/value seam; the SharedPreferences adapter lands with the pane UI. */
interface LayoutStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
}

class InMemoryLayoutStore : LayoutStore {
    val data = LinkedHashMap<String, String>()
    override fun read(key: String) = data[key]
    override fun write(key: String, value: String) { data[key] = value }
    override fun remove(key: String) { data.remove(key) }
}

/** Result of reading a stored layout: either it, or the reason it was not usable. */
sealed interface DecodeResult {
    data class Ok(val layout: PaneLayout) : DecodeResult
    data class Invalid(val reason: String) : DecodeResult
}

/**
 * Versioned JSON for a [PaneLayout]. Decoding never throws: anything wrong (bad JSON, unknown
 * version with no migration, unknown pane kind, a kind outside [allowedKinds], duplicates,
 * non-finite fractions, absurd depth or size) is an [DecodeResult.Invalid] and the caller falls
 * back to a preset. Fractions that are positive but do not sum to 1 are renormalised.
 *
 * [migrations] maps "from version" to a function producing the next version's document; the
 * current schema is v1 and nothing older was ever shipped, so the default map is empty - the
 * chain exists so a v2 can be added without discarding stored v1 layouts.
 */
class LayoutCodec(private val migrations: Map<Int, (JSONObject) -> JSONObject> = emptyMap()) {

    fun encode(l: PaneLayout): String = JSONObject().apply {
        put("v", CURRENT_VERSION)
        put("root", node(l.root))
        put("focus", l.focused.token)
        put("maximised", l.maximised?.token ?: JSONObject.NULL)
    }.toString()

    private fun node(n: LayoutNode): JSONObject = when (n) {
        is LayoutNode.Leaf -> JSONObject().put("t", "leaf").put("pane", n.pane.token)
        is LayoutNode.Split -> JSONObject().put("t", "split").put("axis", n.axis.name)
            .put("children", JSONArray().also { a -> n.children.forEach { a.put(node(it)) } })
            .put("fractions", JSONArray().also { a -> n.fractions.forEach { a.put(it.toDouble()) } })
    }

    fun decode(json: String?, allowedKinds: Set<PaneKind> = PaneKind.entries.toSet()): DecodeResult {
        if (json == null) return DecodeResult.Invalid("nothing stored")
        if (json.length > MAX_CHARS) return DecodeResult.Invalid("stored layout too large")
        return try {
            var obj = JSONObject(json)
            var v = obj.optInt("v", -1)
            if (v < 0) return DecodeResult.Invalid("missing version")
            if (v > CURRENT_VERSION) return DecodeResult.Invalid("written by a newer version ($v)")
            while (v < CURRENT_VERSION) {
                val step = migrations[v] ?: return DecodeResult.Invalid("no migration from v$v")
                obj = step(obj)
                v += 1
            }
            val budget = intArrayOf(MAX_NODES)
            val root = readNode(obj.getJSONObject("root"), 0, budget, allowedKinds)
            val focus = PaneId.parse(obj.optString("focus", null)) ?: return DecodeResult.Invalid("bad focus")
            val max = if (obj.isNull("maximised")) null else PaneId.parse(obj.optString("maximised", null)) ?: return DecodeResult.Invalid("bad maximised")
            DecodeResult.Ok(PaneLayout(root, focus, max))
        } catch (e: Exception) {
            // org.json, require() and cast failures all land here; the message is ours or a parser's, never a secret.
            DecodeResult.Invalid("unreadable: ${e.javaClass.simpleName}")
        }
    }

    private fun readNode(o: JSONObject, depth: Int, budget: IntArray, allowed: Set<PaneKind>): LayoutNode {
        require(depth <= MAX_DEPTH) { "too deep" }
        require(--budget[0] >= 0) { "too many nodes" }
        return when (o.getString("t")) {
            "leaf" -> {
                val id = PaneId.parse(o.getString("pane")) ?: throw IllegalArgumentException("bad pane")
                require(id.kind in allowed) { "pane kind not available" }
                LayoutNode.Leaf(id)
            }
            "split" -> {
                val axis = Axis.entries.firstOrNull { it.name == o.getString("axis") } ?: throw IllegalArgumentException("bad axis")
                val kids = o.getJSONArray("children")
                val fr = o.getJSONArray("fractions")
                require(kids.length() == fr.length() && kids.length() in 2..MAX_NODES) { "bad split arity" }
                val raw = List(fr.length()) { fr.getDouble(it).toFloat() }
                require(raw.all { it.isFinite() && it > 0f }) { "bad fraction" }
                val sum = raw.sum()
                LayoutNode.Split(axis, List(kids.length()) { readNode(kids.getJSONObject(it), depth + 1, budget, allowed) }, raw.map { it / sum })
            }
            else -> throw IllegalArgumentException("bad node type")
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_DEPTH = 12
        const val MAX_NODES = 64
        const val MAX_CHARS = 64 * 1024
    }
}

enum class LayoutSource { STORED, PRESET }

data class LoadedLayout(val layout: PaneLayout, val source: LayoutSource, val fallbackReason: String? = null)

/**
 * Loads/saves layouts per [DisplayFingerprint]. A missing, corrupt, foreign or pane-set-changed
 * record yields the class's placeholder preset (and says why); it is never an exception.
 */
class DockedLayoutRepository(
    private val store: LayoutStore,
    private val codec: LayoutCodec = LayoutCodec(),
    private val allowedKinds: Set<PaneKind> = PaneKind.entries.toSet(),
) {
    fun load(fp: DisplayFingerprint): LoadedLayout {
        val raw = store.read(fp.storageKey)
        val preset = PaneLayoutPresets.forClass(fp.screenClass).layout
        if (raw == null) return LoadedLayout(preset, LayoutSource.PRESET)
        return when (val r = codec.decode(raw, allowedKinds)) {
            is DecodeResult.Ok -> LoadedLayout(r.layout, LayoutSource.STORED)
            is DecodeResult.Invalid -> LoadedLayout(preset, LayoutSource.PRESET, r.reason)
        }
    }

    fun save(fp: DisplayFingerprint, layout: PaneLayout) = store.write(fp.storageKey, codec.encode(layout))

    fun reset(fp: DisplayFingerprint) = store.remove(fp.storageKey)
}
