package dev.aarso.domain.runtime

import dev.aarso.contracts.execution.ExecutionTargetType

/**
 * Fonebrew's provider-neutral execution fabric.
 *
 * This sits ABOVE ExecutionProvider: the broker decides which runtime can satisfy a workload,
 * while the selected provider still executes through the existing execution contract and emits
 * the normal receipts/provenance. Planned providers are representable without being runnable;
 * the broker never selects NEEDS_SETUP/UNSUPPORTED entries.
 */
enum class RuntimeKind {
    ANDROID_NATIVE,
    LINUX_USERSPACE,
    JVM,
    BROWSER,
    WINDOWS_COMPAT,
    ISOLATED_VM,
    REMOTE_RUNNER,
}

enum class RuntimeCapability {
    ANDROID_API,
    SHELL,
    FILE_TRANSFER,
    GIT,
    JVM,
    GRADLE,
    PYTHON,
    NODE,
    NATIVE_TOOLCHAIN,
    ANDROID_SDK,
    BROWSER_HEADLESS,
    SELENIUM,
    WINDOWS_API,
    VM_ISOLATION,
    NETWORK,
    USB_DEVICE,
    CI_DISPATCH,
}

enum class RuntimeLocality { ON_DEVICE, USER_OWNED_REMOTE, EPHEMERAL_CLOUD }

enum class RuntimeAvailability { READY, NEEDS_SETUP, UNSUPPORTED }

/**
 * A truthful description of one execution substrate. [capabilities] are what is available NOW,
 * not an aspirational feature list. A provider that has not been installed/probed must remain
 * NEEDS_SETUP and is never eligible for selection.
 */
data class RuntimeProviderProfile(
    val providerId: String,
    val executionTargetType: ExecutionTargetType,
    val kinds: Set<RuntimeKind>,
    val capabilities: Set<RuntimeCapability>,
    val locality: RuntimeLocality,
    val availability: RuntimeAvailability,
    val architectures: Set<String> = emptySet(),
    val setupHint: String? = null,
) {
    init {
        require(providerId.isNotBlank()) { "RuntimeProviderProfile.providerId must be non-blank." }
        require(kinds.isNotEmpty()) { "RuntimeProviderProfile.kinds must be non-empty." }
        if (availability == RuntimeAvailability.NEEDS_SETUP) {
            require(!setupHint.isNullOrBlank()) {
                "A NEEDS_SETUP runtime must explain what is missing."
            }
        }
    }
}

data class RuntimeRequest(
    val requiredCapabilities: Set<RuntimeCapability>,
    val preferredKinds: List<RuntimeKind> = emptyList(),
    val architecture: String? = null,
    val preferOnDevice: Boolean = true,
    val allowRemote: Boolean = true,
    val allowCompatibilityLayers: Boolean = true,
    val requireIsolation: Boolean = false,
) {
    init {
        architecture?.let { require(it.isNotBlank()) { "architecture must be non-blank when present." } }
    }
}

enum class RuntimeBlocker {
    NOT_READY,
    MISSING_CAPABILITY,
    REMOTE_DISALLOWED,
    COMPATIBILITY_LAYER_DISALLOWED,
    ISOLATION_REQUIRED,
    ARCHITECTURE_MISMATCH,
}

data class RejectedRuntime(
    val providerId: String,
    val blockers: Set<RuntimeBlocker>,
    val missingCapabilities: Set<RuntimeCapability> = emptySet(),
    val setupHint: String? = null,
)

sealed interface RuntimeResolution {
    data class Matched(
        val profile: RuntimeProviderProfile,
        val score: Int,
        val rejected: List<RejectedRuntime>,
    ) : RuntimeResolution

    data class NoMatch(val rejected: List<RejectedRuntime>) : RuntimeResolution
}

/**
 * Deterministic, fail-closed runtime selection.
 *
 * No probing or I/O lives here. Device/SSH/CI/compatibility providers publish truthful profiles,
 * then this class picks a compatible READY profile. Equal scores are broken by providerId so the
 * same inputs always resolve identically.
 */
class RuntimeBroker(private val profiles: () -> List<RuntimeProviderProfile>) {

    fun resolve(request: RuntimeRequest): RuntimeResolution {
        val rejected = mutableListOf<RejectedRuntime>()
        val eligible = mutableListOf<Pair<RuntimeProviderProfile, Int>>()

        profiles().forEach { profile ->
            val blockers = linkedSetOf<RuntimeBlocker>()
            val missing = request.requiredCapabilities - profile.capabilities

            if (profile.availability != RuntimeAvailability.READY) blockers += RuntimeBlocker.NOT_READY
            if (missing.isNotEmpty()) blockers += RuntimeBlocker.MISSING_CAPABILITY
            if (!request.allowRemote && profile.locality != RuntimeLocality.ON_DEVICE) blockers += RuntimeBlocker.REMOTE_DISALLOWED
            if (!request.allowCompatibilityLayers && RuntimeKind.WINDOWS_COMPAT in profile.kinds) {
                blockers += RuntimeBlocker.COMPATIBILITY_LAYER_DISALLOWED
            }
            if (request.requireIsolation && RuntimeCapability.VM_ISOLATION !in profile.capabilities) {
                blockers += RuntimeBlocker.ISOLATION_REQUIRED
            }
            request.architecture?.let { wanted ->
                if (profile.architectures.isNotEmpty() && wanted !in profile.architectures) {
                    blockers += RuntimeBlocker.ARCHITECTURE_MISMATCH
                }
            }

            if (blockers.isNotEmpty()) {
                rejected += RejectedRuntime(
                    providerId = profile.providerId,
                    blockers = blockers,
                    missingCapabilities = missing,
                    setupHint = profile.setupHint,
                )
            } else {
                eligible += profile to score(profile, request)
            }
        }

        val selected = eligible.sortedWith(
            compareByDescending<Pair<RuntimeProviderProfile, Int>> { it.second }
                .thenBy { it.first.providerId }
        ).firstOrNull()

        return if (selected == null) RuntimeResolution.NoMatch(rejected.sortedBy { it.providerId })
        else RuntimeResolution.Matched(selected.first, selected.second, rejected.sortedBy { it.providerId })
    }

    private fun score(profile: RuntimeProviderProfile, request: RuntimeRequest): Int {
        var score = 0

        if (request.preferOnDevice) {
            score += when (profile.locality) {
                RuntimeLocality.ON_DEVICE -> 1000
                RuntimeLocality.USER_OWNED_REMOTE -> 200
                RuntimeLocality.EPHEMERAL_CLOUD -> 100
            }
        }

        val preferredIndex = request.preferredKinds.indexOfFirst { it in profile.kinds }
        if (preferredIndex >= 0) score += 500 - preferredIndex.coerceAtMost(49) * 10

        // Prefer the least-powerful sufficient runtime: fewer extra capabilities means a smaller
        // data/authority surface for the same request.
        score -= (profile.capabilities - request.requiredCapabilities).size

        return score
    }
}

/**
 * Baseline catalogue. It intentionally distinguishes usable orchestration from planned local
 * runtimes: Linux/JVM/browser, Windows compatibility and AVF-style isolated VMs stay NEEDS_SETUP
 * until a concrete provider reports itself ready.
 */
object RuntimeCatalog {
    fun baseline(
        hasTrustedSshHost: Boolean,
        hasCiHost: Boolean,
        localLinuxCapsuleReady: Boolean = false,
        windowsCompatReady: Boolean = false,
        isolatedVmReady: Boolean = false,
    ): List<RuntimeProviderProfile> = listOf(
        RuntimeProviderProfile(
            providerId = "android-native",
            executionTargetType = ExecutionTargetType.LOCAL_ANDROID,
            kinds = setOf(RuntimeKind.ANDROID_NATIVE),
            capabilities = setOf(RuntimeCapability.ANDROID_API, RuntimeCapability.NETWORK, RuntimeCapability.USB_DEVICE),
            locality = RuntimeLocality.ON_DEVICE,
            availability = RuntimeAvailability.READY,
            architectures = setOf("arm64-v8a", "aarch64"),
        ),
        RuntimeProviderProfile(
            providerId = "linux-capsule",
            executionTargetType = ExecutionTargetType.LOCAL_ANDROID,
            kinds = setOf(RuntimeKind.LINUX_USERSPACE, RuntimeKind.JVM, RuntimeKind.BROWSER),
            capabilities = setOf(
                RuntimeCapability.SHELL, RuntimeCapability.FILE_TRANSFER, RuntimeCapability.GIT,
                RuntimeCapability.JVM, RuntimeCapability.GRADLE, RuntimeCapability.PYTHON,
                RuntimeCapability.NODE, RuntimeCapability.NATIVE_TOOLCHAIN,
                RuntimeCapability.BROWSER_HEADLESS, RuntimeCapability.SELENIUM,
                RuntimeCapability.NETWORK,
            ),
            locality = RuntimeLocality.ON_DEVICE,
            availability = if (localLinuxCapsuleReady) RuntimeAvailability.READY else RuntimeAvailability.NEEDS_SETUP,
            architectures = setOf("arm64-v8a", "aarch64"),
            setupHint = if (localLinuxCapsuleReady) null else "Install/provision the Fonebrew Linux capsule runtime.",
        ),
        RuntimeProviderProfile(
            providerId = "windows-compat",
            executionTargetType = ExecutionTargetType.LOCAL_ANDROID,
            kinds = setOf(RuntimeKind.WINDOWS_COMPAT),
            capabilities = setOf(RuntimeCapability.WINDOWS_API, RuntimeCapability.NETWORK, RuntimeCapability.FILE_TRANSFER),
            locality = RuntimeLocality.ON_DEVICE,
            availability = if (windowsCompatReady) RuntimeAvailability.READY else RuntimeAvailability.NEEDS_SETUP,
            architectures = setOf("x86_64", "x86"),
            setupHint = if (windowsCompatReady) null else "Install a separately licensed Wine/translation compatibility pack.",
        ),
        RuntimeProviderProfile(
            providerId = "isolated-vm",
            executionTargetType = ExecutionTargetType.LOCAL_ANDROID,
            kinds = setOf(RuntimeKind.ISOLATED_VM, RuntimeKind.LINUX_USERSPACE),
            capabilities = setOf(
                RuntimeCapability.VM_ISOLATION, RuntimeCapability.SHELL, RuntimeCapability.FILE_TRANSFER,
                RuntimeCapability.NETWORK,
            ),
            locality = RuntimeLocality.ON_DEVICE,
            availability = if (isolatedVmReady) RuntimeAvailability.READY else RuntimeAvailability.NEEDS_SETUP,
            architectures = setOf("aarch64"),
            setupHint = if (isolatedVmReady) null else "This Android build/device has not exposed an approved isolated-VM provider.",
        ),
        RuntimeProviderProfile(
            providerId = "ssh-remote",
            executionTargetType = ExecutionTargetType.SSH_HOST,
            kinds = setOf(RuntimeKind.REMOTE_RUNNER),
            // A trusted SSH connection proves a shell/file-transfer substrate, not the presence
            // of Java/Gradle/Chromium. Tool capabilities must be added by a later host probe.
            capabilities = setOf(RuntimeCapability.SHELL, RuntimeCapability.FILE_TRANSFER, RuntimeCapability.NETWORK),
            locality = RuntimeLocality.USER_OWNED_REMOTE,
            availability = if (hasTrustedSshHost) RuntimeAvailability.READY else RuntimeAvailability.NEEDS_SETUP,
            setupHint = if (hasTrustedSshHost) null else "Connect and trust an SSH host in Fonebrew.",
        ),
        RuntimeProviderProfile(
            providerId = "ci-dispatch",
            executionTargetType = ExecutionTargetType.GITHUB_ACTIONS,
            kinds = setOf(RuntimeKind.REMOTE_RUNNER),
            capabilities = setOf(RuntimeCapability.CI_DISPATCH, RuntimeCapability.NETWORK),
            locality = RuntimeLocality.EPHEMERAL_CLOUD,
            availability = if (hasCiHost) RuntimeAvailability.READY else RuntimeAvailability.NEEDS_SETUP,
            setupHint = if (hasCiHost) null else "Connect a Git/CI host.",
        ),
    )
}
