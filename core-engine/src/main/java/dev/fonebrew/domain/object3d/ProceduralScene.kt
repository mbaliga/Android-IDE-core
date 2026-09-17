// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.object3d

import dev.fonebrew.domain.contracts.extractUnknownFields
import dev.fonebrew.domain.contracts.mergeUnknownFields
import dev.fonebrew.domain.contracts.optJSONObjectOrNull
import dev.fonebrew.domain.contracts.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * docs/design/objects-3d.md §4's "Primitive DSL (preferred)" model — "compact JSON: list of
 * box/sphere/cylinder/cone/torus/plane ops with transforms, colors, and `union` grouping.
 * Interpreted in-viewer by three.js primitives." This file owns the pure Kotlin model, its
 * `org.json` wire codec ([ProceduralSceneCodec]), and its semantic validator
 * ([ProceduralSceneValidator]) — the validator is what powers §4's "invalid output -> one
 * structured retry with the validator's complaint" loop: [ProceduralSceneCodec.decode] only
 * enforces that the JSON is STRUCTURALLY well-formed (known primitive kind, right shape), and
 * deliberately does not reject a structurally-valid-but-nonsensical scene (an out-of-range
 * dimension, a `colorHex` that isn't a hex color) — that is [ProceduralSceneValidator]'s job,
 * so a caller gets back a specific, actionable list of complaints to hand the model on retry
 * rather than a bare parse exception.
 */
enum class PrimitiveKind { BOX, SPHERE, CYLINDER, CONE, TORUS, PLANE }

data class Vec3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val ONE = Vec3(1.0, 1.0, 1.0)
    }
}

data class Transform(
    val translate: Vec3 = Vec3.ZERO,
    val rotateDeg: Vec3 = Vec3.ZERO,
    val scale: Vec3 = Vec3.ONE,
)

/**
 * One primitive op. [colorHex] and [group] are plain strings at the model layer — [PrimitiveOp]
 * itself does not reject a malformed value (that is [ProceduralSceneValidator]'s job) — because
 * the whole point of separating decode from validate is that a decoded-but-invalid scene must
 * still be constructible so the validator can describe exactly what is wrong with it.
 *
 * @param params Kind-specific numeric parameters (e.g. `radius`, `width`/`height`/`depth`) —
 *   an open map rather than one field per possible parameter across six different primitive
 *   kinds, mirroring [dev.fonebrew.contracts.common.CapabilityManifest.limits]'s same open-map
 *   choice for a per-subject-kind vocabulary.
 * @param group Optional `union` grouping key (§4) — ops sharing a non-null `group` value are
 *   unioned by the viewer; null means "not grouped."
 */
data class PrimitiveOp(
    val id: String,
    val kind: PrimitiveKind,
    val transform: Transform = Transform(),
    val colorHex: String? = null,
    val params: Map<String, Double> = emptyMap(),
    val group: String? = null,
)

data class ProceduralScene(
    val schemaVersion: String = "1.0.0",
    val ops: List<PrimitiveOp>,
    val unknownFields: Map<String, Any?> = emptyMap(),
)

/**
 * `org.json` encode/decode for [ProceduralScene], the house pattern every other codec in
 * `dev.fonebrew.domain.contracts`/`dev.fonebrew.domain.thread` already follows (unknown-field
 * round-trip included). This is the wire shape the fenced ```object3d``` chat block (§4) carries
 * when the model chose the DSL_PRIMITIVES method, and what
 * [dev.fonebrew.domain.object3d.GenerationDebugInfo]-shaped `rawOutputText` (see
 * schemas/object3d/object3d-node.schema.json) decodes as JSON text.
 */
object ProceduralSceneCodec {

    private val SCENE_KNOWN_KEYS = setOf("schemaVersion", "ops")

    fun encode(scene: ProceduralScene): JSONObject {
        val obj = JSONObject()
        obj.put("schemaVersion", scene.schemaVersion)
        obj.put("ops", JSONArray(scene.ops.map(::encodeOp)))
        mergeUnknownFields(obj, scene.unknownFields)
        return obj
    }

    fun decode(json: JSONObject): ProceduralScene {
        val opsJson = json.optJSONArray("ops") ?: JSONArray()
        return ProceduralScene(
            schemaVersion = json.optString("schemaVersion", "1.0.0"),
            ops = (0 until opsJson.length()).map { decodeOp(opsJson.getJSONObject(it)) },
            unknownFields = extractUnknownFields(json, SCENE_KNOWN_KEYS),
        )
    }

    fun toJsonString(scene: ProceduralScene): String = encode(scene).toString()

    fun fromJsonString(text: String): ProceduralScene = decode(JSONObject(text))

    private fun encodeOp(op: PrimitiveOp): JSONObject {
        val obj = JSONObject()
        obj.put("id", op.id)
        obj.put("kind", op.kind.name)
        obj.put("transform", encodeTransform(op.transform))
        op.colorHex?.let { obj.put("colorHex", it) }
        if (op.params.isNotEmpty()) obj.put("params", JSONObject(op.params))
        op.group?.let { obj.put("group", it) }
        return obj
    }

    private fun decodeOp(json: JSONObject): PrimitiveOp {
        val kindStr = json.getString("kind")
        val kind = PrimitiveKind.entries.firstOrNull { it.name == kindStr }
            ?: throw IllegalArgumentException("ProceduralScene: unknown primitive kind '$kindStr' — must be one of ${PrimitiveKind.entries.map { it.name }}")
        return PrimitiveOp(
            id = json.getString("id"),
            kind = kind,
            transform = json.optJSONObjectOrNull("transform")?.let(::decodeTransform) ?: Transform(),
            colorHex = json.optStringOrNull("colorHex"),
            params = json.optJSONObjectOrNull("params")?.let(::decodeParams) ?: emptyMap(),
            group = json.optStringOrNull("group"),
        )
    }

    private fun encodeTransform(t: Transform): JSONObject = JSONObject().apply {
        put("translate", encodeVec3(t.translate))
        put("rotateDeg", encodeVec3(t.rotateDeg))
        put("scale", encodeVec3(t.scale))
    }

    private fun decodeTransform(json: JSONObject): Transform = Transform(
        translate = json.optJSONObjectOrNull("translate")?.let(::decodeVec3) ?: Vec3.ZERO,
        rotateDeg = json.optJSONObjectOrNull("rotateDeg")?.let(::decodeVec3) ?: Vec3.ZERO,
        scale = json.optJSONObjectOrNull("scale")?.let(::decodeVec3) ?: Vec3.ONE,
    )

    private fun encodeVec3(v: Vec3): JSONObject = JSONObject().apply {
        put("x", v.x)
        put("y", v.y)
        put("z", v.z)
    }

    private fun decodeVec3(json: JSONObject): Vec3 = Vec3(
        x = json.optDouble("x", 0.0),
        y = json.optDouble("y", 0.0),
        z = json.optDouble("z", 0.0),
    )

    private fun decodeParams(json: JSONObject): Map<String, Double> {
        val map = LinkedHashMap<String, Double>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = json.getDouble(k)
        }
        return map
    }
}

sealed class ProceduralSceneValidation {
    data class Valid(val scene: ProceduralScene) : ProceduralSceneValidation()
    data class Invalid(val reasons: List<String>) : ProceduralSceneValidation()
}

/**
 * Semantic validator for a decoded [ProceduralScene] — §4's "invalid output -> one structured
 * retry with the validator's complaint" gate. [validate] never throws; every problem is
 * accumulated into [ProceduralSceneValidation.Invalid.reasons] so a caller can hand the model the
 * FULL list on retry, not just the first thing that happened to break.
 *
 * The [COLOR_HEX_PATTERN]/[IDENTIFIER_PATTERN] checks below are this file's answer to
 * fixtures/object3d/adversarial/'s "DSL smuggling a URL" case: a model output that sets
 * `colorHex` (or `group`, or `id`) to something URL-shaped (`"https://evil.example/exfil?..."`)
 * is JSON-Schema-valid (the wire schema only knows these are strings — see
 * schemas/object3d/object3d-node.schema.json's `GenerationDebugInfo` $comment) but fails BOTH
 * patterns below, so it is refused here, before the scene is ever handed to the viewer bridge
 * (which would otherwise be the thing actually dereferencing that string).
 */
object ProceduralSceneValidator {

    /** §4: "small models generate this far more reliably... than hundreds of vertex lines" — the
     *  DSL is meant to stay small. A scene claiming hundreds of ops is already off that thesis,
     *  and a pathologically large op count is a resource-exhaustion vector for the viewer. */
    const val MAX_OPS = 256

    /** Generous but finite bound for any single coordinate/dimension, in scene units. Chosen to
     *  comfortably fit any plausible hand-authored or model-authored object while still refusing
     *  a clearly-bogus value (e.g. a stray `1e30`). */
    const val MAX_DIMENSION = 100_000.0

    private val COLOR_HEX_PATTERN = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
    private val IDENTIFIER_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")

    private val REQUIRED_PARAMS: Map<PrimitiveKind, List<String>> = mapOf(
        PrimitiveKind.BOX to listOf("width", "height", "depth"),
        PrimitiveKind.SPHERE to listOf("radius"),
        PrimitiveKind.CYLINDER to listOf("radius", "height"),
        PrimitiveKind.CONE to listOf("radius", "height"),
        PrimitiveKind.TORUS to listOf("radius", "tubeRadius"),
        PrimitiveKind.PLANE to listOf("width", "height"),
    )

    fun validate(scene: ProceduralScene): ProceduralSceneValidation {
        val reasons = mutableListOf<String>()

        if (scene.ops.isEmpty()) reasons += "scene has no ops (at least one primitive is required)"
        if (scene.ops.size > MAX_OPS) reasons += "scene has ${scene.ops.size} ops, exceeding the maximum of $MAX_OPS"

        val seenIds = HashSet<String>()
        for ((index, op) in scene.ops.withIndex()) {
            val where = "ops[$index] (id='${op.id}')"

            if (!IDENTIFIER_PATTERN.matches(op.id)) {
                reasons += "$where: id must match $IDENTIFIER_PATTERN (got '${op.id}')"
            } else if (!seenIds.add(op.id)) {
                reasons += "$where: duplicate id"
            }

            op.group?.let { g ->
                if (!IDENTIFIER_PATTERN.matches(g)) {
                    reasons += "$where: group must match $IDENTIFIER_PATTERN (got '$g') — group values are viewer-side union keys, never URLs or free text"
                }
            }

            op.colorHex?.let { c ->
                if (!COLOR_HEX_PATTERN.matches(c)) {
                    reasons += "$where: colorHex '$c' is not a #RRGGBB or #RRGGBBAA hex color — colors are never URLs or arbitrary strings"
                }
            }

            reasons += validateTransform(where, op.transform)
            reasons += validateParams(where, op.kind, op.params)
        }

        return if (reasons.isEmpty()) ProceduralSceneValidation.Valid(scene) else ProceduralSceneValidation.Invalid(reasons)
    }

    private fun validateTransform(where: String, t: Transform): List<String> {
        val reasons = mutableListOf<String>()
        for ((label, v) in listOf("translate" to t.translate, "rotateDeg" to t.rotateDeg, "scale" to t.scale)) {
            for ((axis, value) in listOf("x" to v.x, "y" to v.y, "z" to v.z)) {
                if (!value.isFinite()) {
                    reasons += "$where: transform.$label.$axis must be finite (got $value)"
                } else if (abs(value) > MAX_DIMENSION) {
                    reasons += "$where: transform.$label.$axis magnitude exceeds $MAX_DIMENSION (got $value)"
                }
            }
        }
        if (t.scale.x == 0.0 || t.scale.y == 0.0 || t.scale.z == 0.0) {
            reasons += "$where: transform.scale must be non-zero on every axis (a zero-scale primitive is degenerate)"
        }
        return reasons
    }

    private fun validateParams(where: String, kind: PrimitiveKind, params: Map<String, Double>): List<String> {
        val reasons = mutableListOf<String>()
        for (key in REQUIRED_PARAMS.getValue(kind)) {
            val value = params[key]
            when {
                value == null -> reasons += "$where: missing required param '$key' for $kind"
                !value.isFinite() -> reasons += "$where: param '$key' must be finite (got $value)"
                value <= 0.0 -> reasons += "$where: param '$key' must be > 0 (got $value)"
                value > MAX_DIMENSION -> reasons += "$where: param '$key' exceeds max dimension $MAX_DIMENSION (got $value)"
            }
        }
        return reasons
    }
}
