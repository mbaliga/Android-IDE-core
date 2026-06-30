package dev.aarso.data

import android.content.Context
import dev.aarso.domain.tree.Bookmarks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the small bits of session state that make the app feel continuous:
 * where the user was in the tree, which model was active, and the lightweight
 * UI preferences. Without this every process death silently reset the chat to
 * an empty screen (and minted a new root on the next send).
 *
 * SharedPreferences, mirroring [ProviderStore]'s shape: synchronous load,
 * StateFlow cache, write-through setters. Everything stays on-device.
 */
class SessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aarso.session", Context.MODE_PRIVATE)

    private val _activeLeafId = MutableStateFlow(prefs.getString(KEY_LEAF, null))
    val activeLeafId: StateFlow<String?> = _activeLeafId.asStateFlow()

    private val _activeModelId = MutableStateFlow(prefs.getString(KEY_MODEL, null))
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val _onboardingDone = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    private val _instrumentsExpanded = MutableStateFlow(prefs.getBoolean(KEY_INSTRUMENTS, false))
    val instrumentsExpanded: StateFlow<Boolean> = _instrumentsExpanded.asStateFlow()

    private val _entropyColoring = MutableStateFlow(prefs.getBoolean(KEY_ENTROPY, true))
    val entropyColoring: StateFlow<Boolean> = _entropyColoring.asStateFlow()

    private val _spatialMapSeen = MutableStateFlow(prefs.getBoolean(KEY_SPATIAL_MAP, false))
    val spatialMapSeen: StateFlow<Boolean> = _spatialMapSeen.asStateFlow()

    // Appearance (theme engine): mode is "SYSTEM" / "LIGHT" / "DARK"; accent is "#RRGGBB".
    // Default is a clean, neutral light Material 3 look (owner: "generic, neat and well spaced");
    // the dark Aeon palette + violet remain selectable in Appearance.
    private val _themeMode = MutableStateFlow(prefs.getString(KEY_THEME_MODE, "LIGHT") ?: "LIGHT")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _accentColor = MutableStateFlow(prefs.getString(KEY_ACCENT, DEFAULT_ACCENT) ?: DEFAULT_ACCENT)
    val accentColor: StateFlow<String> = _accentColor.asStateFlow()

    // Ambient grain texture intensity, 0f (off) … 1f. Applied to the base surface only.
    private val _textureIntensity = MutableStateFlow(prefs.getFloat(KEY_TEXTURE, 0f))
    val textureIntensity: StateFlow<Float> = _textureIntensity.asStateFlow()

    // Second stop of the ambient background gradient ("#RRGGBB"); blank = no gradient.
    private val _gradientColor = MutableStateFlow(prefs.getString(KEY_GRADIENT, "") ?: "")
    val gradientColor: StateFlow<String> = _gradientColor.asStateFlow()

    // Bookmarked conversation roots (the "Starred" filter in Chats).
    private val _bookmarkedRoots = MutableStateFlow(prefs.getStringSet(KEY_BOOKMARKS, emptySet())?.toSet() ?: emptySet())
    val bookmarkedRoots: StateFlow<Set<String>> = _bookmarkedRoots.asStateFlow()

    // Per-conversation project label (the "Projects" grouping in Chats). Persisted as a set of
    // "rootId\u0001project" entries; absent = unassigned. Local only.
    private val _conversationProjects = MutableStateFlow(loadConversationProjects())
    val conversationProjects: StateFlow<Map<String, String>> = _conversationProjects.asStateFlow()

    // Per-conversation open count (how many times the chat was opened) — the honest source for
    // the Conversations room's "most used" sort. Persisted as "rootId\u0001count" entries; absent
    // = never opened. Local only; nothing leaves the device (binding rule 1).
    private val _conversationOpens = MutableStateFlow(loadConversationOpens())
    val conversationOpens: StateFlow<Map<String, Int>> = _conversationOpens.asStateFlow()

    // Default council mode for new conversations: "SINGLE" / "PERSONAS" / "MODELS".
    private val _councilDefault = MutableStateFlow(prefs.getString(KEY_COUNCIL_DEFAULT, "SINGLE") ?: "SINGLE")
    val councilDefault: StateFlow<String> = _councilDefault.asStateFlow()

    // Progressive disclosure tier: "CORE" / "STUDIO" / "POWER" (docs/design/disclosure.md).
    // Defaults to POWER so existing installs see no change; the onboarding intent step
    // sets it for new users, and Settings can change it any time.
    private val _disclosureTier = MutableStateFlow(prefs.getString(KEY_DISCLOSURE, "POWER") ?: "POWER")
    val disclosureTier: StateFlow<String> = _disclosureTier.asStateFlow()

    // Free-tier list refresh. OFF by default (on-device default; no hidden network). Turning it
    // on is an explicit, consented online fetch; the source URL is shown to the user (watched).
    private val _freeTierAutoUpdate = MutableStateFlow(prefs.getBoolean(KEY_FT_AUTO, false))
    val freeTierAutoUpdate: StateFlow<Boolean> = _freeTierAutoUpdate.asStateFlow()
    private val _freeTierSourceUrl = MutableStateFlow(prefs.getString(KEY_FT_URL, DEFAULT_FT_URL) ?: DEFAULT_FT_URL)
    val freeTierSourceUrl: StateFlow<String> = _freeTierSourceUrl.asStateFlow()

    fun setFreeTierAutoUpdate(on: Boolean) {
        prefs.edit().putBoolean(KEY_FT_AUTO, on).apply()
        _freeTierAutoUpdate.value = on
    }

    fun setFreeTierSourceUrl(url: String) {
        prefs.edit().putString(KEY_FT_URL, url).apply()
        _freeTierSourceUrl.value = url
    }

    fun setActiveLeafId(id: String?) {
        prefs.edit().putString(KEY_LEAF, id).apply()
        _activeLeafId.value = id
    }

    fun setActiveModelId(id: String?) {
        prefs.edit().putString(KEY_MODEL, id).apply()
        _activeModelId.value = id
    }

    fun setOnboardingDone() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        _onboardingDone.value = true
    }

    fun setInstrumentsExpanded(expanded: Boolean) {
        prefs.edit().putBoolean(KEY_INSTRUMENTS, expanded).apply()
        _instrumentsExpanded.value = expanded
    }

    fun setEntropyColoring(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENTROPY, enabled).apply()
        _entropyColoring.value = enabled
    }

    /** One-time spatial-map overlay; Settings can reset it to show the map again. */
    fun setSpatialMapSeen(seen: Boolean) {
        prefs.edit().putBoolean(KEY_SPATIAL_MAP, seen).apply()
        _spatialMapSeen.value = seen
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
        _themeMode.value = mode
    }

    fun setAccentColor(hex: String) {
        prefs.edit().putString(KEY_ACCENT, hex).apply()
        _accentColor.value = hex
    }

    fun setTextureIntensity(value: Float) {
        val v = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_TEXTURE, v).apply()
        _textureIntensity.value = v
    }

    fun setGradientColor(hex: String) {
        prefs.edit().putString(KEY_GRADIENT, hex).apply()
        _gradientColor.value = hex
    }

    fun toggleBookmark(rootId: String) {
        val next = Bookmarks.toggle(_bookmarkedRoots.value, rootId)
        prefs.edit().putStringSet(KEY_BOOKMARKS, next).apply()
        _bookmarkedRoots.value = next
    }

    /** Assign (or clear, with a blank/null label) the project a conversation belongs to. */
    fun setConversationProject(rootId: String, project: String?) {
        val next = _conversationProjects.value.toMutableMap()
        val label = project?.trim().orEmpty()
        if (label.isEmpty()) next.remove(rootId) else next[rootId] = label
        prefs.edit().putStringSet(
            KEY_CONV_PROJECTS,
            next.entries.map { "${it.key}\u0001${it.value}" }.toSet(),
        ).apply()
        _conversationProjects.value = next
    }

    private fun loadConversationProjects(): Map<String, String> =
        prefs.getStringSet(KEY_CONV_PROJECTS, emptySet()).orEmpty()
            .mapNotNull { e -> e.split('\u0001', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()

    /** Record that a conversation was opened; increments its open count. Feeds the Conversations
     *  room's "most used" sort honestly — no fabricated usage. */
    fun recordConversationOpen(rootId: String) {
        if (rootId.isBlank()) return
        val next = _conversationOpens.value.toMutableMap()
        next[rootId] = (next[rootId] ?: 0) + 1
        prefs.edit().putStringSet(
            KEY_CONV_OPENS,
            next.entries.map { "${it.key}\u0001${it.value}" }.toSet(),
        ).apply()
        _conversationOpens.value = next
    }

    private fun loadConversationOpens(): Map<String, Int> =
        prefs.getStringSet(KEY_CONV_OPENS, emptySet()).orEmpty()
            .mapNotNull { e ->
                e.split('\u0001', limit = 2).takeIf { it.size == 2 }
                    ?.let { parts -> parts[1].toIntOrNull()?.let { parts[0] to it } }
            }
            .toMap()

    fun setCouncilDefault(mode: String) {
        prefs.edit().putString(KEY_COUNCIL_DEFAULT, mode).apply()
        _councilDefault.value = mode
    }

    fun setDisclosureTier(tier: String) {
        prefs.edit().putString(KEY_DISCLOSURE, tier).apply()
        _disclosureTier.value = tier
    }

    companion object {
        // A clean, generic blue (a shipped, AA-verified preset) — neutral default in place of
        // the Aeon violet, which stays available as a preset.
        const val DEFAULT_ACCENT = "#4DA3FF"

        // Default free-tier source: the catalog file on the repo's main branch. Editable in the
        // UI (point it at wherever the list is published). The fetch only happens with consent.
        const val DEFAULT_FT_URL =
            "https://raw.githubusercontent.com/mbaliga/mobile-llm/main/app/src/main/assets/free_tiers.json"
        private const val KEY_FT_AUTO = "freeTierAutoUpdate"
        private const val KEY_FT_URL = "freeTierSourceUrl"

        private const val KEY_LEAF = "activeLeafId"
        private const val KEY_MODEL = "activeModelId"
        private const val KEY_ONBOARDED = "onboardingDone"
        private const val KEY_INSTRUMENTS = "instrumentsExpanded"
        private const val KEY_ENTROPY = "entropyColoring"
        private const val KEY_SPATIAL_MAP = "spatialMapSeen"
        private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_ACCENT = "accentColor"
        private const val KEY_TEXTURE = "textureIntensity"
        private const val KEY_GRADIENT = "gradientColor"
        private const val KEY_BOOKMARKS = "bookmarkedRoots"
        private const val KEY_CONV_PROJECTS = "conversationProjects"
        private const val KEY_CONV_OPENS = "conversationOpens"
        private const val KEY_COUNCIL_DEFAULT = "councilDefault"
        private const val KEY_DISCLOSURE = "disclosureTier"
    }
}
