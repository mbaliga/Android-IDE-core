package dev.aarso.domain.model

import dev.aarso.domain.template.TemplateId

/**
 * Describes a model the user can switch a conversation to (handoff §3). The
 * conversation is just text on a path, so any model can continue from any node;
 * what differs per model — and must be handled on a switch — is captured here:
 * its chat template, its tokenizer (token counts differ across models, §2), and
 * its context window (the path must fit).
 *
 * [runtime] says what actually executes the model. In this phase only
 * [Runtime.ECHO_DEV] runs; [Runtime.LOCAL_GGUF] specs are listed but not yet
 * loadable (the native engine is unbuilt), which is itself honest information to
 * surface in the picker.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val family: String,
    val contextWindow: Int,
    val tokenizerId: String,
    val templateId: TemplateId,
    val runtime: Runtime,
    /** Set for [Runtime.CLOUD]: the CloudProvider this spec routes to. */
    val providerId: String? = null,
    /** Set for downloaded [Runtime.LOCAL_GGUF]: absolute path to the .gguf file. */
    val modelPath: String? = null,
    /** A "watched object" (§5c) — every cloud model is one; on-device models are not. */
    val watched: Boolean = false,
) {
    val isOnDevice: Boolean get() = runtime == Runtime.ECHO_DEV || runtime == Runtime.LOCAL_GGUF
}

enum class Runtime {
    /** Dev stand-in that echoes; runs without the native library. */
    ECHO_DEV,

    /** Real local GGUF via llama.cpp — not loadable until the native build. */
    LOCAL_GGUF,

    /** A user-configured cloud provider (opt-in, watched, never a default). */
    CLOUD,
}
