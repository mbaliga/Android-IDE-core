// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// LanguageLaneContracts.kt — the "language lane" domain's shared shapes (WP-9). Like WP-6's
// search domain, no ratified spec doc or schema corpus for language lanes existed before this
// pass: 01_VALIDATION_REPORT.md §E1 names "language pack, DAP host, capsules" as WP-level
// contract additions "where the relevant WP touches them" rather than pre-authoring them in
// WP-1, and 04_ARCHITECTURE_CONTRACTS.md's own artifact inventory has no language-lane section.
// This file is that contract, grounded in three concrete sources rather than invention: the
// WP-9 brief text itself (06_WORK_PACKAGES.md), the B1/B3 platform-feasibility corrections in
// 01_VALIDATION_REPORT.md (Android's W^X exec restriction and Play's interpreter/VM carve-out —
// the reason [ToolchainDeliveryMechanism] has five members and not "download and exec a
// binary"), and this codebase's own already-shipped IDE surface (RepoWorkLoop, CodeLens —
// per CLAUDE.md's repo map) that a language lane eventually plugs into.
//
// Scope discipline (binding, per the WP-9 brief): "Wire TS + Python (interpreter lane) as far
// as JVM-verifiable; Rust/C++ lanes are contract + REMOTE-mechanism only in this pass." Every
// type below is language-agnostic; [BuiltInLanguagePacks] (this file's bottom) is the only place
// TypeScript/Python-specific facts appear, and Rust/C++ get one REMOTE-only fixture each, not a
// second implementation path.
//
// Toolchain constraint (binding, matching every other domain's contract file): stdlib +
// java.time.Instant only. No kotlinx-serialization, no kotlinx-datetime, no Android imports, no
// kotlinx-coroutines Flow (session lifecycle here is a state machine driven by discrete events,
// the same encoding ExecutionContracts.kt/LoopRuntimeContracts.kt use for their own lifecycles,
// not an observed stream in this file's scope).
//
// COMPILATION STATUS: VERIFIED — compiled as part of core-engine's real Gradle build
// (:core-engine:compileFullDebugKotlin / :core-engine:testFullDebugUnitTest) during WP-9
// (2026-08-07).

package dev.aarso.contracts.language

private val LANGUAGE_ID_PATTERN = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
private val SEMVER_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z-.]+)?(\\+[0-9A-Za-z-.]+)?$")

/**
 * A deliberately closed, bounded set — the LSP methods a phone-side client actually needs to
 * negotiate for, not the protocol's full method surface. Extending this set is additive/MINOR,
 * matching every other capability-ID registry's `bumpRule` convention in this constellation.
 */
enum class LspCapability {
    HOVER, COMPLETION, GO_TO_DEFINITION, FIND_REFERENCES, DIAGNOSTICS, RENAME,
    FORMATTING, CODE_ACTIONS, SIGNATURE_HELP, DOCUMENT_SYMBOLS, WORKSPACE_SYMBOLS,
}

/** The Debug Adapter Protocol's operations a phone-side DAP host actually drives. */
enum class DapCapability {
    SET_BREAKPOINTS, CONDITIONAL_BREAKPOINTS, STEP_IN_OUT_OVER, EVALUATE_EXPRESSION,
    VARIABLES_INSPECTION, TERMINATE, RESTART,
}

/**
 * §B1/B3, 01_VALIDATION_REPORT.md: Android's W^X exec restriction (SELinux blocks `execve()`/
 * executable-mmap of app-writable-storage files since Android 10, targetSdk 29+) means
 * "download an arbitrary native binary and exec it" is not a mechanism at all, on any
 * distribution flavor — this enum has five members precisely because the ruling names five,
 * not because a sixth ("downloaded native exec") was omitted by oversight.
 */
enum class ToolchainDeliveryMechanism {
    /** Bundled Node/CPython runtime interpreting downloaded *scripts* — Play's interpreter/VM carve-out applies; the only mechanism legal on both flavors without an APK-release round-trip. */
    INTERPRETER_SCRIPTS,
    /** Ships additional native code *through Play itself* (Play Dynamic Feature Delivery) — a Play-flavor-only channel. */
    PLAY_DYNAMIC_FEATURE,
    /** Per-ABI `jniLibs`, versioned with the app release — legal on both flavors, but "defeats download a capsule" (B1): an update means a full app release, not an independent capsule fetch. */
    BUNDLED_JNILIBS,
    /** A separately-installed signed APK via `PackageInstaller` — sideload-flavor-only; Play does not permit an app to install arbitrary companion APKs as a toolchain delivery path. */
    CAPSULE_APK,
    /** No local execution at all — SSH/CI. Always legal on every flavor, the only mechanism this pass wires for Rust/C++ (WP-9 brief). */
    REMOTE,
}

/** Local duplication of `dev.aarso.contracts.loops.DistFlavor`'s wire values, not an import — this repo's established convention for small enums shared only nominally across domain boundaries (see e.g. `AuthorityContracts.kt`'s own `AuthorityRung` header note). */
enum class DeliveryFlavor(val wireValue: String) { FULL("full"), PLAY("play") }

/**
 * §20/B3: the toolchain capsule's own manifest — what a language pack's compiler/interpreter/
 * debugger payload actually is and how it reaches the device. `targetAbis` is only meaningful
 * (and required non-empty) when [deliveryMechanism] is [ToolchainDeliveryMechanism.BUNDLED_JNILIBS].
 */
data class ToolchainCapsuleManifest(
    val capsuleId: String,
    val languageId: String,
    val deliveryMechanism: ToolchainDeliveryMechanism,
    val semanticVersion: String,
    val targetAbis: List<String> = emptyList(),
    val digestSha256Hex: String? = null,
) {
    init {
        require(capsuleId.isNotBlank()) { "ToolchainCapsuleManifest.capsuleId must be non-blank." }
        require(LANGUAGE_ID_PATTERN.matches(languageId)) { "ToolchainCapsuleManifest.languageId must match $LANGUAGE_ID_PATTERN (got '$languageId')." }
        require(SEMVER_PATTERN.matches(semanticVersion)) { "ToolchainCapsuleManifest.semanticVersion must be SemVer (got '$semanticVersion')." }
        if (deliveryMechanism == ToolchainDeliveryMechanism.BUNDLED_JNILIBS) {
            require(targetAbis.isNotEmpty()) { "ToolchainCapsuleManifest: BUNDLED_JNILIBS requires a non-empty targetAbis list." }
        }
        digestSha256Hex?.let { require(Regex("^[0-9a-f]{64}$").matches(it)) { "ToolchainCapsuleManifest.digestSha256Hex must be 64 lowercase hex chars when present." } }
    }
}

/**
 * §4/§9's "LanguagePack manifest": what a lane declares about itself — the language(s) it
 * handles, its LSP/DAP servers (by capsule reference, not embedded), and the negotiable
 * capability sets it's willing to offer. `fileExtensions` drives [DiagnosticsOwnership]'s
 * conflict detection below.
 */
data class LanguagePackManifest(
    val packId: String,
    val displayName: String,
    val languageIds: List<String>,
    val fileExtensions: List<String>,
    val semanticVersion: String,
    val lspCapsuleId: String?,
    val dapCapsuleId: String?,
    val declaredLspCapabilities: Set<LspCapability> = emptySet(),
    val declaredDapCapabilities: Set<DapCapability> = emptySet(),
) {
    init {
        require(packId.isNotBlank()) { "LanguagePackManifest.packId must be non-blank." }
        require(displayName.isNotBlank()) { "LanguagePackManifest.displayName must be non-blank." }
        require(languageIds.isNotEmpty()) { "LanguagePackManifest.languageIds must be non-empty." }
        require(languageIds.all { LANGUAGE_ID_PATTERN.matches(it) }) { "LanguagePackManifest.languageIds must all match $LANGUAGE_ID_PATTERN — got $languageIds." }
        require(fileExtensions.isNotEmpty()) { "LanguagePackManifest.fileExtensions must be non-empty." }
        require(fileExtensions.all { it.startsWith(".") }) { "LanguagePackManifest.fileExtensions must all start with '.' — got $fileExtensions." }
        require(SEMVER_PATTERN.matches(semanticVersion)) { "LanguagePackManifest.semanticVersion must be SemVer (got '$semanticVersion')." }
        if (declaredLspCapabilities.isNotEmpty()) requireNotNull(lspCapsuleId) { "LanguagePackManifest: declaredLspCapabilities requires a non-null lspCapsuleId." }
        if (declaredDapCapabilities.isNotEmpty()) requireNotNull(dapCapsuleId) { "LanguagePackManifest: declaredDapCapabilities requires a non-null dapCapsuleId." }
    }
}

/** §9's "task/launch typed definitions" — a typed task (build/lint/test/run), never a raw opaque shell string alone. */
data class TaskDefinition(
    val taskId: String,
    val label: String,
    val command: String,
    val args: List<String> = emptyList(),
    val workingDirectoryRelativePath: String = ".",
) {
    init {
        require(taskId.isNotBlank()) { "TaskDefinition.taskId must be non-blank." }
        require(label.isNotBlank()) { "TaskDefinition.label must be non-blank." }
        require(command.isNotBlank()) { "TaskDefinition.command must be non-blank." }
    }
}

/** §9's "task/launch typed definitions" — a typed debug launch. `preLaunchTaskId`, when present, MUST name a real [TaskDefinition.taskId] (a cross-list obligation this constructor cannot verify alone, same limitation every other domain's contract files document for their own cross-references). */
data class LaunchConfiguration(
    val launchId: String,
    val label: String,
    val languageId: String,
    val dapCapsuleId: String,
    val program: String,
    val args: List<String> = emptyList(),
    val preLaunchTaskId: String? = null,
) {
    init {
        require(launchId.isNotBlank()) { "LaunchConfiguration.launchId must be non-blank." }
        require(label.isNotBlank()) { "LaunchConfiguration.label must be non-blank." }
        require(LANGUAGE_ID_PATTERN.matches(languageId)) { "LaunchConfiguration.languageId must match $LANGUAGE_ID_PATTERN (got '$languageId')." }
        require(dapCapsuleId.isNotBlank()) { "LaunchConfiguration.dapCapsuleId must be non-blank." }
        require(program.isNotBlank()) { "LaunchConfiguration.program must be non-blank." }
    }
}

/**
 * §9's TypeScript/Python "interpreter lane" fixtures, wired "as far as JVM-verifiable" (WP-9
 * brief) — and one REMOTE-only fixture apiece for Rust/C++, "contract + REMOTE-mechanism only
 * in this pass." Concrete instances, not abstract shapes, so [dev.aarso.domain.language.
 * DiagnosticsOwnership] and [dev.aarso.domain.language.ToolchainDeliveryLegality] have something
 * real to check against rather than only hand-built test fixtures.
 */
object BuiltInLanguagePacks {
    val TYPESCRIPT = LanguagePackManifest(
        packId = "lang.typescript", displayName = "TypeScript / JavaScript",
        languageIds = listOf("typescript", "javascript"),
        fileExtensions = listOf(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"),
        semanticVersion = "1.0.0",
        lspCapsuleId = "capsule.typescript-language-server", dapCapsuleId = "capsule.node-debug",
        declaredLspCapabilities = setOf(
            LspCapability.HOVER, LspCapability.COMPLETION, LspCapability.GO_TO_DEFINITION,
            LspCapability.FIND_REFERENCES, LspCapability.DIAGNOSTICS, LspCapability.RENAME,
            LspCapability.FORMATTING, LspCapability.CODE_ACTIONS, LspCapability.SIGNATURE_HELP,
        ),
        declaredDapCapabilities = setOf(
            DapCapability.SET_BREAKPOINTS, DapCapability.CONDITIONAL_BREAKPOINTS,
            DapCapability.STEP_IN_OUT_OVER, DapCapability.EVALUATE_EXPRESSION,
            DapCapability.VARIABLES_INSPECTION, DapCapability.TERMINATE, DapCapability.RESTART,
        ),
    )

    val PYTHON = LanguagePackManifest(
        packId = "lang.python", displayName = "Python",
        languageIds = listOf("python"),
        fileExtensions = listOf(".py", ".pyi"),
        semanticVersion = "1.0.0",
        lspCapsuleId = "capsule.pyright", dapCapsuleId = "capsule.debugpy",
        declaredLspCapabilities = setOf(
            LspCapability.HOVER, LspCapability.COMPLETION, LspCapability.GO_TO_DEFINITION,
            LspCapability.FIND_REFERENCES, LspCapability.DIAGNOSTICS, LspCapability.SIGNATURE_HELP,
            LspCapability.DOCUMENT_SYMBOLS, LspCapability.WORKSPACE_SYMBOLS,
        ),
        declaredDapCapabilities = setOf(
            DapCapability.SET_BREAKPOINTS, DapCapability.CONDITIONAL_BREAKPOINTS,
            DapCapability.STEP_IN_OUT_OVER, DapCapability.EVALUATE_EXPRESSION,
            DapCapability.VARIABLES_INSPECTION, DapCapability.TERMINATE,
        ),
    )

    /** "Rust/C++ lanes are contract + REMOTE-mechanism only in this pass" — no local LSP/DAP capsule reference, `lspCapsuleId`/`dapCapsuleId` both null, no declared capabilities to negotiate locally yet. */
    val RUST = LanguagePackManifest(
        packId = "lang.rust", displayName = "Rust", languageIds = listOf("rust"),
        fileExtensions = listOf(".rs"), semanticVersion = "1.0.0",
        lspCapsuleId = null, dapCapsuleId = null,
    )

    /** rust-analyzer/clangd/lldb-dap all run on ARM64 Android per 01_VALIDATION_REPORT.md's own evidence, but "lldb-dap/codelldb is NOT turnkey" for Android specifically (custom-vendored build needed) — REMOTE-only until that vendoring happens, same as Rust above. */
    val CPP = LanguagePackManifest(
        packId = "lang.cpp", displayName = "C/C++", languageIds = listOf("cpp", "c"),
        fileExtensions = listOf(".cpp", ".cc", ".cxx", ".h", ".hpp", ".c"), semanticVersion = "1.0.0",
        lspCapsuleId = null, dapCapsuleId = null,
    )

    val ALL: List<LanguagePackManifest> = listOf(TYPESCRIPT, PYTHON, RUST, CPP)
}
