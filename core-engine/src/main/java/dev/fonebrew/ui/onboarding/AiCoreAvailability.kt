package dev.fonebrew.ui.onboarding

import android.content.Context
import com.google.ai.edge.aicore.GenerationConfig
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether this phone's on-device Gemini Nano (AICore) actually works — never assumed from the
 * device model alone. This SDK revision (`0.0.1-exp01`) has no confirmed dedicated
 * "is it available" call, so the probe is the honest fallback: try to build a
 * [GenerativeModel] and warm it up with [GenerativeModel.prepareInferenceEngine], and treat any
 * [GenerativeAIException] (unsupported device, needs a system update, no space to download the
 * model, …) as "not available here." A generous timeout, since first-time preparation can
 * legitimately include a model download over the network — this call is only ever made from the
 * onboarding wizard, which shows its own "checking…" state while this runs.
 */
object AiCoreAvailability {

    suspend fun probe(context: Context, timeoutMs: Long = 30_000): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                val config = GenerationConfig.Builder().apply {
                    this.context = context.applicationContext
                }.build()
                val model = GenerativeModel(generationConfig = config)
                model.prepareInferenceEngine()
                model.close()
                true
            }.getOrElse { false }
        } ?: false
    }
}
