package dev.aarso.inference

import dev.aarso.data.LocalModel
import dev.aarso.data.LocalModelStore
import dev.aarso.data.ProviderStore
import dev.aarso.domain.cloud.CloudProvider
import dev.aarso.domain.model.ModelSpec
import dev.aarso.domain.model.Runtime
import dev.aarso.domain.template.TemplateId

/**
 * The set of models a conversation can switch between (handoff §3): downloaded
 * on-device models plus the user's configured cloud providers. **On-device is
 * always the default**; cloud models are marked watched and never selected
 * automatically.
 *
 * [devSpecs] carries the echo stand-ins in debug builds only (release ships no
 * fake engine); which model a session starts with is decided by
 * [dev.aarso.domain.model.DefaultModelPolicy], which may resolve to null — the
 * explicit "no model yet" state the first-run setup card renders.
 */
class ModelRegistry(
    private val providers: ProviderStore,
    private val locals: LocalModelStore,
    private val devSpecs: List<ModelSpec> = emptyList(),
) {

    fun allSpecs(): List<ModelSpec> =
        locals.models.value.map { it.toSpec() } +
            devSpecs +
            providers.providers.value.map { it.toSpec() }

    fun byId(id: String): ModelSpec? = allSpecs().firstOrNull { it.id == id }

    val models: List<ModelSpec> get() = allSpecs()
}

/** The echo dev stand-ins (debug builds only): run the chat loop with no native lib. */
fun echoDevSpecs(): List<ModelSpec> = listOf(
    ModelSpec(
        id = "echo-chatml",
        displayName = "Echo · ChatML · 8k (dev)",
        family = "echo",
        contextWindow = 8192,
        tokenizerId = "echo:chatml",
        templateId = TemplateId.CHATML,
        runtime = Runtime.ECHO_DEV,
    ),
    ModelSpec(
        id = "echo-tiny",
        displayName = "Echo · tiny · 64-tok ctx (dev)",
        family = "echo",
        contextWindow = 64,
        tokenizerId = "echo:tiny",
        templateId = TemplateId.PLAIN,
        runtime = Runtime.ECHO_DEV,
    ),
)

fun LocalModel.toSpec(): ModelSpec = ModelSpec(
    id = "local:$name",
    // The raw filename minus the extension: short enough for the top bar, and
    // on-device needs no marker — only cloud is special (watched, rule 2).
    displayName = name.removeSuffix(".gguf"),
    family = "gguf",
    contextWindow = 8192, // default; the native loader reads the real value at load
    tokenizerId = "llama.cpp:$name",
    templateId = TemplateId.CHATML, // default; future: detect from GGUF metadata
    runtime = Runtime.LOCAL_GGUF,
    modelPath = path,
)

fun CloudProvider.toSpec(): ModelSpec = ModelSpec(
    id = "cloud:$id",
    displayName = "☁ $displayName · $model",
    family = kind.name.lowercase(),
    contextWindow = contextWindow,
    tokenizerId = "cloud:$model",
    templateId = TemplateId.PLAIN, // unused: cloud engines format payloads natively
    runtime = Runtime.CLOUD,
    providerId = id,
    watched = true,
)
