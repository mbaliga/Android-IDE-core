package dev.fonebrew.domain.extension

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What an extension *is* (docs/design/extensions.md). An extension is a signed-able, content-
 * addressable bundle with a manifest that states, up front and in plain data, **what it is** and
 * **which capabilities it asks for**. Nothing in a manifest is code. This file is the declarative
 * half: parsing and validating manifests. Pure domain (org.json) — no Android, JVM-testable.
 *
 * Kinds are an allow-list. A kind the build does not implement is a *validation failure*, never a
 * silent no-op, so an extension can't appear installed while doing nothing (legibility rule).
 * [SCRIPT] is reserved for the sandboxed-script runtime; it is deliberately NOT accepted yet.
 */
enum class ExtensionKind(val wire: String, val implemented: Boolean) {
    THEME("theme", implemented = true),
    /** Reserved: sandboxed script runtime (design §5). Rejected until the runtime lands. */
    SCRIPT("script", implemented = false),
    ;

    companion object {
        fun fromWire(wire: String): ExtensionKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The capability vocabulary an extension may *request*. A request is not a grant: the user grants
 * (or doesn't) through the existing capability-authority model (`domain/authority`,
 * docs/ratified/CAPABILITY_AUTHORITY_MODEL.md), and every grant is revocable and visible.
 */
enum class ExtensionCapability(val wire: String) {
    /** Change theme mode / accent / texture / gradient. The only capability a theme may hold. */
    APPEARANCE_WRITE("appearance.write"),
    ;

    companion object {
        fun fromWire(wire: String): ExtensionCapability? = entries.firstOrNull { it.wire == wire }
    }
}

data class ExtensionManifest(
    val id: String,
    val name: String,
    val version: String,
    val kind: ExtensionKind,
    val author: String,
    val license: String,
    val description: String,
    val capabilities: Set<ExtensionCapability>,
    /** The raw `theme` object, present iff [kind] is [ExtensionKind.THEME]; parsed by [ThemePack]. */
    val themePayload: JSONObject? = null,
) {
    companion object {
        const val FORMAT = "fonebrew-extension"
        const val FORMAT_VERSION = 1

        private val ID = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$")
        private val SEMVER = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z.-]+)?$")

        /** Which capabilities each kind may request — a theme can never ask for anything but appearance. */
        private val ALLOWED: Map<ExtensionKind, Set<ExtensionCapability>> = mapOf(
            ExtensionKind.THEME to setOf(ExtensionCapability.APPEARANCE_WRITE),
        )

        fun parse(json: String): ParseResult {
            val obj = try {
                JSONObject(json)
            } catch (e: JSONException) {
                return ParseResult.Failure(listOf("not valid JSON: ${e.message?.take(80)}"))
            }
            val errors = mutableListOf<String>()

            if (obj.optString("format") != FORMAT) errors += "format must be \"$FORMAT\""
            if (obj.optInt("formatVersion", -1) != FORMAT_VERSION) errors += "formatVersion must be $FORMAT_VERSION"

            val id = obj.optString("id")
            if (!ID.matches(id)) errors += "id must be a lowercase dotted identifier like dev.example.theme.abyss"
            val name = obj.optString("name").trim()
            if (name.isEmpty()) errors += "name is required"
            if (name.length > 60) errors += "name is longer than 60 characters"
            val version = obj.optString("version")
            if (!SEMVER.matches(version)) errors += "version must be semver (e.g. 1.0.0)"
            val author = obj.optString("author").trim()
            if (author.isEmpty()) errors += "author is required"
            val license = obj.optString("license").trim()
            if (license.isEmpty()) errors += "license is required (an SPDX id such as Apache-2.0)"

            val kindWire = obj.optString("kind")
            val kind = ExtensionKind.fromWire(kindWire)
            if (kind == null) {
                errors += "unknown kind \"$kindWire\""
            } else if (!kind.implemented) {
                errors += "kind \"${kind.wire}\" is not supported by this build yet"
            }

            val caps = LinkedHashSet<ExtensionCapability>()
            val capsJson: JSONArray = obj.optJSONArray("capabilities") ?: JSONArray()
            for (i in 0 until capsJson.length()) {
                val wire = capsJson.optString(i)
                val cap = ExtensionCapability.fromWire(wire)
                if (cap == null) errors += "unknown capability \"$wire\"" else caps += cap
            }
            if (kind != null && kind.implemented) {
                val allowed = ALLOWED[kind].orEmpty()
                (caps - allowed).forEach { errors += "kind \"${kind.wire}\" may not request \"${it.wire}\"" }
                (allowed - caps).forEach { errors += "kind \"${kind.wire}\" must declare \"${it.wire}\"" }
            }

            var payload: JSONObject? = null
            if (kind == ExtensionKind.THEME) {
                payload = obj.optJSONObject("theme")
                if (payload == null) errors += "theme extensions need a \"theme\" object"
            }

            if (errors.isNotEmpty() || kind == null) return ParseResult.Failure(errors)
            return ParseResult.Success(
                ExtensionManifest(
                    id = id, name = name, version = version, kind = kind, author = author,
                    license = license, description = obj.optString("description").trim(),
                    capabilities = caps, themePayload = payload,
                ),
            )
        }
    }

    sealed interface ParseResult {
        data class Success(val manifest: ExtensionManifest) : ParseResult
        data class Failure(val errors: List<String>) : ParseResult
    }
}
