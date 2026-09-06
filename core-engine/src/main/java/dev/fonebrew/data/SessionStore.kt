package dev.fonebrew.data

import android.content.Context
import dev.fonebrew.domain.tree.Bookmarks
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

    // Gates the one-time LoopCatalog seed (LoopCatalogSeeder) -- deliberately "seeded, ever" not
    // "list is empty": a user who deletes every seeded loop must not get them silently repopulated.
    private val _loopCatalogSeeded = MutableStateFlow(prefs.getBoolean(KEY_LOOP_CATALOG_SEEDED, false))
    val loopCatalogSeeded: StateFlow<Boolean> = _loopCatalogSeeded.asStateFlow()

    private val _instrumentsExpanded = MutableStateFlow(prefs.getBoolean(KEY_INSTRUMENTS, false))
    val instrumentsExpanded: StateFlow<Boolean> = _instrumentsExpanded.asStateFlow()

    private val _entropyColoring = MutableStateFlow(prefs.getBoolean(KEY_ENTROPY, true))
    val entropyColoring: StateFlow<Boolean> = _entropyColoring.asStateFlow()

    // On-screen Ctrl-C button in Terminal — on by default; a Settings toggle for anyone whose
    // keyboard already has a physical/IME control key (e.g. Clackpad) to hide the now-redundant
    // button. No keyboard detection here — it's a manual opt-out, not an automatic one. The
    // /ctrlc slash command always works regardless of this setting.
    private val _terminalCtrlCButton = MutableStateFlow(prefs.getBoolean(KEY_TERMINAL_CTRL_C, true))
    val terminalCtrlCButton: StateFlow<Boolean> = _terminalCtrlCButton.asStateFlow()

    private val _spatialMapSeen = MutableStateFlow(prefs.getBoolean(KEY_SPATIAL_MAP, false))
    val spatialMapSeen: StateFlow<Boolean> = _spatialMapSeen.asStateFlow()

    // Appearance (theme engine): mode is "SYSTEM" / "LIGHT" / "DARK"; accent is "#RRGGBB".
    // Default is the dark Hyle/Aeon AMOLED palette (CLAUDE.md invariant: "AMOLED black ground";
    // superseded 2026-07-18 — the prior "clean, neutral light" default meant the app shipped
    // with none of the Hyle glass-pane/texture/haptic effects visible out of the box, since
    // those are designed against the dark palette). Light stays selectable in Appearance.
    private val _themeMode = MutableStateFlow(prefs.getString(KEY_THEME_MODE, "DARK") ?: "DARK")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _accentColor = MutableStateFlow(prefs.getString(KEY_ACCENT, DEFAULT_ACCENT) ?: DEFAULT_ACCENT)
    val accentColor: StateFlow<String> = _accentColor.asStateFlow()

    // Chat home's header status chip (replaces the fixed Me·Myself·I avatar entry point, which
    // remains reachable from Settings): "NONE" / "SOVEREIGNTY" / "QUOTA" / "TIME". Defaults to
    // NONE — the header shows nothing until the user opts a fact into view, matching the
    // minimalism direction from the 2026-07-19 UX audit rather than presuming what's useful.
    private val _headerIndicator = MutableStateFlow(prefs.getString(KEY_HEADER_INDICATOR, "NONE") ?: "NONE")
    val headerIndicator: StateFlow<String> = _headerIndicator.asStateFlow()

    // A room's HyleTabBar can sit at the top or bottom of its own Column: "TOP" / "BOTTOM".
    // Universal default here; a per-room override (below) wins when set. Defaults to TOP —
    // unchanged from every room's shipped layout today.
    private val _tabBarPosition = MutableStateFlow(prefs.getString(KEY_TAB_BAR_POSITION, "TOP") ?: "TOP")
    val tabBarPosition: StateFlow<String> = _tabBarPosition.asStateFlow()

    // Per-room tab-bar position override, same "roomIdvalue" persisted-set shape as
    // conversationProjects. A room absent here just follows the universal default above.
    private val _roomTabBarPosition = MutableStateFlow(loadRoomTabBarPosition())
    val roomTabBarPosition: StateFlow<Map<String, String>> = _roomTabBarPosition.asStateFlow()

    // Ambient grain texture intensity, 0f (off) … 1f. Applied to the base surface only.
    // Defaults on (not 0f) so the "rough surface" register is part of the shipped look, not an
    // opt-in a new user has to go find in Settings — still a user-adjustable, turn-off-able knob.
    private val _textureIntensity = MutableStateFlow(prefs.getFloat(KEY_TEXTURE, 0.6f))
    val textureIntensity: StateFlow<Float> = _textureIntensity.asStateFlow()

    // Second stop of the ambient background gradient ("#RRGGBB"); blank = no gradient.
    private val _gradientColor = MutableStateFlow(prefs.getString(KEY_GRADIENT, "") ?: "")
    val gradientColor: StateFlow<String> = _gradientColor.asStateFlow()

    // Set once, by the onboarding wizard, after AiCoreAvailability confirms the phone's
    // on-device Gemini Nano actually works — never assumed true just because the device
    // looks capable. ModelRegistry only lists an AICORE_NANO spec while this is true.
    private val _aiCoreEnabled = MutableStateFlow(prefs.getBoolean(KEY_AICORE, false))
    val aiCoreEnabled: StateFlow<Boolean> = _aiCoreEnabled.asStateFlow()

    // Bookmarked conversation roots (the "Starred" filter in Chats).
    private val _bookmarkedRoots = MutableStateFlow(prefs.getStringSet(KEY_BOOKMARKS, emptySet())?.toSet() ?: emptySet())
    val bookmarkedRoots: StateFlow<Set<String>> = _bookmarkedRoots.asStateFlow()

    // Archived conversation roots — backs search's `is:archived` facet (FONEBREW_SEARCH_SPEC.md
    // §6.1). Same shape as bookmarkedRoots on purpose; this is the flag only, not an archive
    // management UI (no swipe-to-archive surface exists yet — out of scope for search).
    private val _archivedRoots = MutableStateFlow(prefs.getStringSet(KEY_ARCHIVED, emptySet())?.toSet() ?: emptySet())
    val archivedRoots: StateFlow<Set<String>> = _archivedRoots.asStateFlow()

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

    // Model catalog refresh — same shape/contract as the free-tier refresh above: OFF by
    // default, an explicit consented fetch, source URL shown to the user (watched).
    private val _modelCatalogAutoUpdate = MutableStateFlow(prefs.getBoolean(KEY_MC_AUTO, false))
    val modelCatalogAutoUpdate: StateFlow<Boolean> = _modelCatalogAutoUpdate.asStateFlow()
    private val _modelCatalogSourceUrl = MutableStateFlow(prefs.getString(KEY_MC_URL, DEFAULT_MC_URL) ?: DEFAULT_MC_URL)
    val modelCatalogSourceUrl: StateFlow<String> = _modelCatalogSourceUrl.asStateFlow()

    fun setModelCatalogAutoUpdate(on: Boolean) {
        prefs.edit().putBoolean(KEY_MC_AUTO, on).apply()
        _modelCatalogAutoUpdate.value = on
    }

    fun setModelCatalogSourceUrl(url: String) {
        prefs.edit().putString(KEY_MC_URL, url).apply()
        _modelCatalogSourceUrl.value = url
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

    fun setLoopCatalogSeeded() {
        prefs.edit().putBoolean(KEY_LOOP_CATALOG_SEEDED, true).apply()
        _loopCatalogSeeded.value = true
    }

    fun setInstrumentsExpanded(expanded: Boolean) {
        prefs.edit().putBoolean(KEY_INSTRUMENTS, expanded).apply()
        _instrumentsExpanded.value = expanded
    }

    fun setEntropyColoring(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENTROPY, enabled).apply()
        _entropyColoring.value = enabled
    }

    fun setTerminalCtrlCButton(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TERMINAL_CTRL_C, enabled).apply()
        _terminalCtrlCButton.value = enabled
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

    fun setHeaderIndicator(mode: String) {
        prefs.edit().putString(KEY_HEADER_INDICATOR, mode).apply()
        _headerIndicator.value = mode
    }

    fun setTabBarPosition(position: String) {
        prefs.edit().putString(KEY_TAB_BAR_POSITION, position).apply()
        _tabBarPosition.value = position
    }

    /** [position] "TOP"/"BOTTOM", or null to clear the override and fall back to the universal default. */
    fun setRoomTabBarPosition(roomId: String, position: String?) {
        val next = dev.fonebrew.domain.RoomTabBarPosition.set(_roomTabBarPosition.value, roomId, position)
        prefs.edit().putStringSet(
            KEY_ROOM_TAB_BAR_POSITION,
            next.entries.map { "${it.key}${it.value}" }.toSet(),
        ).apply()
        _roomTabBarPosition.value = next
    }

    /** The effective position for [roomId]: its own override if set, else the universal default. */
    fun tabBarPositionFor(roomId: String): String =
        dev.fonebrew.domain.RoomTabBarPosition.effective(_roomTabBarPosition.value, roomId, _tabBarPosition.value)

    private fun loadRoomTabBarPosition(): Map<String, String> =
        prefs.getStringSet(KEY_ROOM_TAB_BAR_POSITION, emptySet()).orEmpty()
            .mapNotNull { e -> e.split('', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()

    fun setTextureIntensity(value: Float) {
        val v = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_TEXTURE, v).apply()
        _textureIntensity.value = v
    }

    fun setGradientColor(hex: String) {
        prefs.edit().putString(KEY_GRADIENT, hex).apply()
        _gradientColor.value = hex
    }

    fun setAiCoreEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AICORE, enabled).apply()
        _aiCoreEnabled.value = enabled
    }

    fun toggleBookmark(rootId: String) {
        val next = Bookmarks.toggle(_bookmarkedRoots.value, rootId)
        prefs.edit().putStringSet(KEY_BOOKMARKS, next).apply()
        _bookmarkedRoots.value = next
    }

    fun toggleArchived(rootId: String) {
        val next = Bookmarks.toggle(_archivedRoots.value, rootId)
        prefs.edit().putStringSet(KEY_ARCHIVED, next).apply()
        _archivedRoots.value = next
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

    // THREAD_TOPOLOGY_PLAN.md WP4: Settings → Gestures — three independent disable toggles
    // (binding constraint 4: "an entry in Settings → Gestures where it can be disabled"), one per
    // channel the drag detector arbitrates. Default ON (the gesture ships as the primary path;
    // every channel's tappable equivalent stays available regardless of these switches).
    private val _gestureVerdictDragEnabled = MutableStateFlow(prefs.getBoolean(KEY_GESTURE_VERDICT, true))
    val gestureVerdictDragEnabled: StateFlow<Boolean> = _gestureVerdictDragEnabled.asStateFlow()

    private val _gestureQuoteReplyEnabled = MutableStateFlow(prefs.getBoolean(KEY_GESTURE_QUOTE_REPLY, true))
    val gestureQuoteReplyEnabled: StateFlow<Boolean> = _gestureQuoteReplyEnabled.asStateFlow()

    private val _gestureRadialFanEnabled = MutableStateFlow(prefs.getBoolean(KEY_GESTURE_RADIAL, true))
    val gestureRadialFanEnabled: StateFlow<Boolean> = _gestureRadialFanEnabled.asStateFlow()

    fun setGestureVerdictDragEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_GESTURE_VERDICT, on).apply()
        _gestureVerdictDragEnabled.value = on
    }

    fun setGestureQuoteReplyEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_GESTURE_QUOTE_REPLY, on).apply()
        _gestureQuoteReplyEnabled.value = on
    }

    fun setGestureRadialFanEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_GESTURE_RADIAL, on).apply()
        _gestureRadialFanEnabled.value = on
    }

    // THREAD_TOPOLOGY_PLAN.md WP9: the graph observer's off-by-default gate (owner decision 5 —
    // "observer ships built but INERT behind an off-by-default settings toggle, AarsoCaptureSettings
    // pattern"). [dev.fonebrew.data.ThreadObserver] reads this via a lambda, not a captured snapshot,
    // so flipping it here takes effect on the observer's next call without any extra wiring.
    private val _observerEnabled = MutableStateFlow(prefs.getBoolean(KEY_OBSERVER, false))
    val observerEnabled: StateFlow<Boolean> = _observerEnabled.asStateFlow()

    fun setObserverEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_OBSERVER, on).apply()
        _observerEnabled.value = on
    }

    // Lane G / owner ruling 2026-09-06 on open-ux-decisions.md item G (G1-MODIFIED): the per-turn
    // inline cost line is real, but only ever shown behind an intentional, default-OFF toggle
    // (owner's words: "Cost always visible has to be a toggle turned on intentionally as it takes
    // up screen space and will make the interface look more cluttered"). Off by default — same
    // shape as [observerEnabled]/[entropyColoring] above; the Cost facet and every ledger-view
    // panel (Instruments, Me·Myself·I) stay unaffected, this only gates the inline chat line.
    private val _perTurnCostInChat = MutableStateFlow(prefs.getBoolean(KEY_PER_TURN_COST, false))
    val perTurnCostInChat: StateFlow<Boolean> = _perTurnCostInChat.asStateFlow()

    fun setPerTurnCostInChat(on: Boolean) {
        prefs.edit().putBoolean(KEY_PER_TURN_COST, on).apply()
        _perTurnCostInChat.value = on
    }

    companion object {
        // A clean, generic blue (a shipped, AA-verified preset) — neutral default in place of
        // the Aeon violet, which stays available as a preset.
        const val DEFAULT_ACCENT = "#4DA3FF"

        // Default free-tier source: Nooz's shared `ai-catalogue/free-tiers.json` — the
        // constellation's canonical, continually-refreshed copy (see that repo's
        // ai-catalogue/README.md); Aarso no longer hand-maintains its own free-tier pipeline.
        // Editable in the UI (point it at wherever the list is published). The fetch only
        // happens with consent. `main` is nooz's real canonical branch as of nooz PR #1 (its
        // pre-init placeholder history and the short-lived `claude/app-build-d1f9s6` line are
        // both superseded — see nooz's STATE.md D16/D17 and the PR #2 close comment there).
        const val DEFAULT_FT_URL =
            "https://raw.githubusercontent.com/mbaliga/nooz/main/ai-catalogue/free-tiers.json"
        private const val KEY_FT_AUTO = "freeTierAutoUpdate"
        private const val KEY_FT_URL = "freeTierSourceUrl"

        // Default model-catalog source: Nooz's shared `ai-catalogue/models.json` — same
        // provenance/branch note as DEFAULT_FT_URL above.
        const val DEFAULT_MC_URL =
            "https://raw.githubusercontent.com/mbaliga/nooz/main/ai-catalogue/models.json"
        private const val KEY_MC_AUTO = "modelCatalogAutoUpdate"
        private const val KEY_MC_URL = "modelCatalogSourceUrl"

        private const val KEY_LEAF = "activeLeafId"
        private const val KEY_MODEL = "activeModelId"
        private const val KEY_ONBOARDED = "onboardingDone"
        private const val KEY_LOOP_CATALOG_SEEDED = "loopCatalogSeeded"
        private const val KEY_INSTRUMENTS = "instrumentsExpanded"
        private const val KEY_ENTROPY = "entropyColoring"
        private const val KEY_TERMINAL_CTRL_C = "terminalCtrlCButton"
        private const val KEY_SPATIAL_MAP = "spatialMapSeen"
        private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_HEADER_INDICATOR = "headerIndicator"
        private const val KEY_TAB_BAR_POSITION = "tabBarPosition"
        private const val KEY_ROOM_TAB_BAR_POSITION = "roomTabBarPosition"
        private const val KEY_ACCENT = "accentColor"
        private const val KEY_TEXTURE = "textureIntensity"
        private const val KEY_GRADIENT = "gradientColor"
        private const val KEY_AICORE = "aiCoreEnabled"
        private const val KEY_BOOKMARKS = "bookmarkedRoots"
        private const val KEY_ARCHIVED = "archivedRoots"
        private const val KEY_CONV_PROJECTS = "conversationProjects"
        private const val KEY_CONV_OPENS = "conversationOpens"
        private const val KEY_COUNCIL_DEFAULT = "councilDefault"
        private const val KEY_DISCLOSURE = "disclosureTier"
        private const val KEY_GESTURE_VERDICT = "gestureVerdictDragEnabled"
        private const val KEY_GESTURE_QUOTE_REPLY = "gestureQuoteReplyEnabled"
        private const val KEY_GESTURE_RADIAL = "gestureRadialFanEnabled"
        private const val KEY_OBSERVER = "threadObserverEnabled"
        private const val KEY_PER_TURN_COST = "perTurnCostInChat"
    }
}
