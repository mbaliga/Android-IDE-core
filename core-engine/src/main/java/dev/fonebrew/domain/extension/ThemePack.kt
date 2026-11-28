package dev.fonebrew.domain.extension

import org.json.JSONObject

/**
 * A theme extension's payload: the same four appearance knobs the in-app ThemePicker already
 * exposes (mode, accent, texture, ambient gradient) — nothing more. A theme pack never carries
 * code and can never reach beyond appearance (its manifest may only request
 * [ExtensionCapability.APPEARANCE_WRITE]). Accent legibility is NOT re-checked here on purpose:
 * Hyle's ramp derivation + contrast clamp already keep any accent AA-legible in both modes
 * downstream, so a pack can't produce an unreadable UI by choosing a bad accent.
 */
data class ThemePack(
    val manifest: ExtensionManifest,
    /** `SYSTEM`, `LIGHT` or `DARK` — the exact strings `SessionStore.themeMode` stores. */
    val mode: String,
    /** `#RRGGBB`. */
    val accent: String,
    /** 0..1, `SessionStore.textureIntensity`'s range. */
    val texture: Float,
    /** `#RRGGBB`, or null for no ambient gradient. */
    val gradient: String?,
) {
    /** The exact calls the app makes to apply this theme — kept as data so it is JVM-testable. */
    data class Apply(val mode: String, val accent: String, val texture: Float, val gradient: String)

    /** True when the app's current appearance is exactly this pack — how the picker marks the active chip. */
    fun isActive(mode: String, accent: String, texture: Float, gradient: String): Boolean {
        val a = toApply()
        return a.mode == mode && a.accent.equals(accent, ignoreCase = true) &&
            kotlin.math.abs(a.texture - texture) < 0.005f && a.gradient.equals(gradient, ignoreCase = true)
    }

    fun toApply(): Apply = Apply(mode = mode, accent = accent.uppercase(), texture = texture, gradient = gradient?.uppercase().orEmpty())

    companion object {
        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
        private val MODES = setOf("SYSTEM", "LIGHT", "DARK")

        fun parse(json: String): Result {
            val manifest = when (val r = ExtensionManifest.parse(json)) {
                is ExtensionManifest.ParseResult.Failure -> return Result.Failure(r.errors)
                is ExtensionManifest.ParseResult.Success -> r.manifest
            }
            if (manifest.kind != ExtensionKind.THEME) return Result.Failure(listOf("not a theme extension"))
            val t: JSONObject = manifest.themePayload ?: return Result.Failure(listOf("theme object missing"))
            val errors = mutableListOf<String>()

            val mode = t.optString("mode")
            if (mode !in MODES) errors += "theme.mode must be one of SYSTEM, LIGHT, DARK"
            val accent = t.optString("accent")
            if (!HEX.matches(accent)) errors += "theme.accent must be #RRGGBB"
            val texture = t.optDouble("texture", Double.NaN)
            if (texture.isNaN() || texture < 0.0 || texture > 1.0) errors += "theme.texture must be a number from 0 to 1"
            val gradient = if (t.has("gradient") && !t.isNull("gradient")) t.optString("gradient") else null
            if (gradient != null && !HEX.matches(gradient)) errors += "theme.gradient must be #RRGGBB or omitted"

            if (errors.isNotEmpty()) return Result.Failure(errors)
            return Result.Success(ThemePack(manifest, mode, accent, texture.toFloat(), gradient))
        }
    }

    sealed interface Result {
        data class Success(val pack: ThemePack) : Result
        data class Failure(val errors: List<String>) : Result
    }
}

/**
 * Turns a set of bundled/imported theme JSON documents into the list the picker shows. Invalid
 * documents are *reported*, not dropped silently: the picker can say which pack failed and why.
 */
object ThemePackCatalog {
    data class Loaded(val packs: List<ThemePack>, val rejected: List<Rejected>)
    data class Rejected(val source: String, val errors: List<String>)

    fun load(documents: Map<String, String>): Loaded {
        val packs = mutableListOf<ThemePack>()
        val rejected = mutableListOf<Rejected>()
        val seen = HashSet<String>()
        for ((source, json) in documents.toSortedMap()) {
            when (val r = ThemePack.parse(json)) {
                is ThemePack.Result.Success ->
                    if (seen.add(r.pack.manifest.id)) packs += r.pack
                    else rejected += Rejected(source, listOf("duplicate extension id ${r.pack.manifest.id}"))
                is ThemePack.Result.Failure -> rejected += Rejected(source, r.errors)
            }
        }
        return Loaded(packs.sortedBy { it.manifest.name.lowercase() }, rejected)
    }
}
