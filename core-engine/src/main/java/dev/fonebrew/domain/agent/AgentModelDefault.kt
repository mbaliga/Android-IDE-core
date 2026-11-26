package dev.fonebrew.domain.agent

import dev.fonebrew.domain.model.ModelSpec

/**
 * Which model the agent starts with. Binding rule 2: on-device is always the default and cloud is
 * opt-in per use, never preselected. So the default is the first runnable ON-DEVICE model; if only
 * cloud models are runnable the default is `null` and the user must tap a cloud model explicitly
 * (the Run button stays disabled until they do), which is the opt-in.
 */
object AgentModelDefault {
    fun defaultModelId(runnable: List<ModelSpec>): String? = runnable.firstOrNull { it.isOnDevice }?.id

    /** True when there are runnable models but none is on-device, so the screen must say that
     *  choosing a model means choosing a cloud (watched) one. */
    fun needsExplicitCloudChoice(runnable: List<ModelSpec>): Boolean =
        runnable.isNotEmpty() && runnable.none { it.isOnDevice }
}
