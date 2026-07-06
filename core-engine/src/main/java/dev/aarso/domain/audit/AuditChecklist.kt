package dev.aarso.domain.audit

/**
 * Audit — a **to-do list of checks, not a scanner**. Aarso deliberately does *not* embed
 * heavy static analysers, linters, or crawlers; each item is a piece of honest advice the
 * owner can *act on*, and its action fires a chat prompt that runs the real tool
 * ([AuditChecklist.promptFor]). The result is recorded by hand (or, later, by an external
 * QA app) — the app never claims a check passed on its own.
 *
 * The whole surface is legible for the same reason the rest of Aarso is: the checklist is
 * a plain list of [AuditCheck]s, the status transitions are pure, and nothing here touches
 * Android or I/O. This file is the machine-verified part; the *running* of a check is
 * owner-verified (the build env has no device, and we don't ship the scanners).
 */

/** Where a single [AuditCheck] currently stands. Owner-recorded, never auto-asserted. */
enum class AuditStatus { PENDING, RUNNING, PASSED, FAILED, SKIPPED }

/** The axis a check belongs to — used only to group the list and pick the prompt template. */
enum class AuditCategory { TESTS, LINT, BUILD, ACCESSIBILITY, I18N, SECURITY, OFFLINE, PERFORMANCE, PROVENANCE }

/**
 * One line item on the audit to-do list. [description] is a single honest sentence about
 * *what good looks like*; the check is run out-of-band via [AuditChecklist.promptFor].
 */
data class AuditCheck(
    val id: String,
    val title: String,
    val description: String,
    val category: AuditCategory,
    val status: AuditStatus = AuditStatus.PENDING,
)

/**
 * The default checklist plus the pure operations over it. No Android, no I/O — the list and
 * its transitions are exhaustively unit-tested; the prompts are text, not execution.
 */
object AuditChecklist {

    /**
     * The starter checklist — one item per axis worth auditing before a release. Ids are
     * stable and unique (they key [withStatus]). Descriptions are one honest line each; none
     * claims the app runs the check itself.
     */
    fun default(): List<AuditCheck> = listOf(
        AuditCheck(
            id = "tests-green",
            title = "Unit tests green",
            description = "The JVM gate passes — every domain + data-layer test is green.",
            category = AuditCategory.TESTS,
        ),
        AuditCheck(
            id = "lint-clean",
            title = "Lint clean",
            description = "No new lint errors; warnings triaged, not ignored.",
            category = AuditCategory.LINT,
        ),
        AuditCheck(
            id = "release-build",
            title = "Release build succeeds",
            description = "The release variant assembles/bundles without error.",
            category = AuditCategory.BUILD,
        ),
        AuditCheck(
            id = "a11y-sweep",
            title = "TalkBack / non-gesture sweep",
            description = "Every surface is reachable and labelled without relying on gestures or colour alone.",
            category = AuditCategory.ACCESSIBILITY,
        ),
        AuditCheck(
            id = "rtl-pseudoloc",
            title = "RTL + pseudolocalization",
            description = "Layout survives RTL mirroring and expanded pseudolocalized strings; nothing clips or overlaps.",
            category = AuditCategory.I18N,
        ),
        AuditCheck(
            id = "no-hardcoded-strings",
            title = "No hard-coded user-facing strings",
            description = "User-visible text comes from resources, not string literals in code.",
            category = AuditCategory.I18N,
        ),
        AuditCheck(
            id = "secrets-safe",
            title = "Secrets never logged or exported",
            description = "API keys stay Keystore-encrypted; never logged, and never in any default export.",
            category = AuditCategory.SECURITY,
        ),
        AuditCheck(
            id = "offline-state-grid",
            title = "Offline state-grid correct",
            description = "Every screen has an honest loading / empty / error / offline state — no dead spinners.",
            category = AuditCategory.OFFLINE,
        ),
        AuditCheck(
            id = "perf-no-main-thread-inference",
            title = "60fps · no main-thread inference",
            description = "Generation and heavy work run off the main thread; scrolling stays smooth.",
            category = AuditCategory.PERFORMANCE,
        ),
        AuditCheck(
            id = "provenance-watched",
            title = "Every external touch is watched",
            description = "Each cloud/off-device path is visibly marked a watched object — no hidden fallback.",
            category = AuditCategory.PROVENANCE,
        ),
    )

    /**
     * The chat-prompt text that would actually **run** [check] — actionable and specific to
     * the check's [AuditCategory]. This is the whole point of Audit-as-to-do-list: tapping
     * Run pastes one of these into Chat, where the model (with repo/agent access) does the
     * real work and reports back. Never blank.
     */
    fun promptFor(check: AuditCheck): String = when (check.category) {
        AuditCategory.TESTS ->
            "Run `./gradlew :app:testFullDebugUnitTest :app:testPlayDebugUnitTest :hyle:test` and " +
                "report any failures with the offending test name + the smallest fix."
        AuditCategory.LINT ->
            "Run the Android lint task on the app module and report each error/warning with its " +
                "file:line and a suggested fix; call out anything newly introduced."
        AuditCategory.BUILD ->
            "Assemble the release variant (`./gradlew :app:assembleFullDebug` / `:app:bundlePlayRelease`) " +
                "and report any build error with the failing task and the cause."
        AuditCategory.ACCESSIBILITY ->
            "Do a TalkBack / non-gesture sweep: walk every screen with the screen reader, list any " +
                "control that is unreachable, unlabelled, or relies on colour or a gesture alone, and propose the fix."
        AuditCategory.I18N ->
            "Enable RTL and pseudolocalization, then report any clipped, overlapping, or mirrored-wrong " +
                "layout — and grep for hard-coded user-facing strings that should be resources."
        AuditCategory.SECURITY ->
            "Audit that API keys stay Keystore-encrypted and never appear in logs or any default export: " +
                "grep for key material in logging and export paths and report every leak with its location."
        AuditCategory.OFFLINE ->
            "Turn off the network and walk each screen: report any surface missing an honest " +
                "loading / empty / error / offline state, or stuck in a dead spinner, with the fix."
        AuditCategory.PERFORMANCE ->
            "Profile for jank: confirm generation and heavy work never run on the main thread, report any " +
                "frame drops or ANRs during scroll/typing, and name the offending call site."
        AuditCategory.PROVENANCE ->
            "Trace every off-device path (cloud providers, remote hosts) and confirm each is visibly marked a " +
                "watched object with no hidden fallback; list any place provenance is unmarked or wrong."
    }

    /** A tally of the checklist by [AuditStatus] — the header summary line reads from this. */
    data class Counts(
        val total: Int,
        val passed: Int,
        val failed: Int,
        val pending: Int,
        val running: Int,
        val skipped: Int,
    )

    /** Count [checks] by status. Pure; a straight fold over the list. */
    fun summary(checks: List<AuditCheck>): Counts = Counts(
        total = checks.size,
        passed = checks.count { it.status == AuditStatus.PASSED },
        failed = checks.count { it.status == AuditStatus.FAILED },
        pending = checks.count { it.status == AuditStatus.PENDING },
        running = checks.count { it.status == AuditStatus.RUNNING },
        skipped = checks.count { it.status == AuditStatus.SKIPPED },
    )

    /**
     * Return a copy of [checks] with the check whose id is [id] set to [status]; every other
     * item is left exactly as it was. Pure and immutable — the input list is never mutated,
     * and an unknown [id] is a no-op (returns an equivalent list).
     */
    fun withStatus(checks: List<AuditCheck>, id: String, status: AuditStatus): List<AuditCheck> =
        checks.map { if (it.id == id) it.copy(status = status) else it }
}
