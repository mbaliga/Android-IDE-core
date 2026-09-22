package dev.aarso.domain.runtime

/**
 * Fonebrew-side reference to a workspace selected through Fylz/SAF.
 *
 * Fonebrew must not reinterpret document IDs as filesystem paths or reimplement provider logic.
 * The URI is handed to Android's ContentResolver / workspace adapters as an opaque capability.
 */
data class FylzWorkspaceRef(
    val treeUri: String,
    val displayName: String? = null,
    val readOnly: Boolean = false,
) {
    init {
        require(treeUri.startsWith("content://")) { "FylzWorkspaceRef requires a content:// tree URI." }
    }
}

enum class AssayNeed {
    SHELL,
    JVM,
    GRADLE,
    PYTHON,
    NODE,
    BROWSER_HEADLESS,
    SELENIUM,
    ANDROID_SDK,
    NETWORK,
    VM_ISOLATION,
}

/**
 * Neutral view of a test workload requested by Assay. Fonebrew routes it; Assay still decides
 * whether the resulting artifacts constitute evidence/proof.
 */
data class AssayWorkload(
    val requestId: String,
    val needs: Set<AssayNeed>,
    val requireIsolation: Boolean = false,
) {
    init {
        require(requestId.isNotBlank()) { "AssayWorkload.requestId must be non-blank." }
    }
}

object AssayRuntimeMapping {
    fun request(workload: AssayWorkload): RuntimeRequest = RuntimeRequest(
        requiredCapabilities = workload.needs.mapTo(linkedSetOf()) {
            when (it) {
                AssayNeed.SHELL -> RuntimeCapability.SHELL
                AssayNeed.JVM -> RuntimeCapability.JVM
                AssayNeed.GRADLE -> RuntimeCapability.GRADLE
                AssayNeed.PYTHON -> RuntimeCapability.PYTHON
                AssayNeed.NODE -> RuntimeCapability.NODE
                AssayNeed.BROWSER_HEADLESS -> RuntimeCapability.BROWSER_HEADLESS
                AssayNeed.SELENIUM -> RuntimeCapability.SELENIUM
                AssayNeed.ANDROID_SDK -> RuntimeCapability.ANDROID_SDK
                AssayNeed.NETWORK -> RuntimeCapability.NETWORK
                AssayNeed.VM_ISOLATION -> RuntimeCapability.VM_ISOLATION
            }
        },
        preferredKinds = when {
            AssayNeed.BROWSER_HEADLESS in workload.needs || AssayNeed.SELENIUM in workload.needs ->
                listOf(RuntimeKind.BROWSER, RuntimeKind.LINUX_USERSPACE, RuntimeKind.REMOTE_RUNNER)
            AssayNeed.JVM in workload.needs || AssayNeed.GRADLE in workload.needs ->
                listOf(RuntimeKind.JVM, RuntimeKind.LINUX_USERSPACE, RuntimeKind.REMOTE_RUNNER)
            else -> listOf(RuntimeKind.LINUX_USERSPACE, RuntimeKind.REMOTE_RUNNER)
        },
        requireIsolation = workload.requireIsolation,
    )
}
