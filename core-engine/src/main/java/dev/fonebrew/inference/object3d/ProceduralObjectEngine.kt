package dev.fonebrew.inference.object3d

import dev.fonebrew.domain.council.Generator
import dev.fonebrew.domain.object3d.ObjParser
import dev.fonebrew.domain.object3d.ProceduralScene
import dev.fonebrew.domain.object3d.ProceduralSceneCodec
import dev.fonebrew.domain.object3d.ProceduralSceneValidation
import dev.fonebrew.domain.object3d.ProceduralSceneValidator

/**
 * docs/design/objects-3d.md §4 — the on-device ("ChatGPT-style") generation path. Prompts the
 * **active chat engine** ([generator] — a [dev.fonebrew.inference.EngineGenerator] wrapping
 * whatever [dev.fonebrew.inference.InferenceEngine] the chat is currently on, the exact seam
 * `GitConnect`/`CodeLens` already use for a one-shot model call) for a fenced ```object3d```
 * block, extracts it, decodes+validates it against the landed pure validators
 * ([ProceduralSceneValidator] for the preferred primitive DSL, [ObjParser] for the accepted raw
 * OBJ text), and — on a validator complaint — makes exactly ONE structured retry that hands the
 * model back its own mistake verbatim, before failing honestly (CLAUDE.md rule 6: never claim
 * on-device behaviour works when it didn't).
 *
 * Provenance is always [dev.fonebrew.domain.provenance.ProvenanceState.LOCAL] — [generator] is
 * whatever engine the chat is already on (on-device by default per binding rule 2); nothing here
 * ever dials out on its own.
 *
 * Pure Kotlin apart from the one [generator] seam, which is a `fun interface`
 * ([dev.fonebrew.domain.council.Generator]) — a JVM test supplies a queue of canned completions
 * and asserts the whole "invalid -> retry-with-complaint -> valid (or honest failure)" shape
 * end to end with no real model, network, or Android dependency.
 */
class ProceduralObjectEngine(private val generator: Generator) {

    suspend fun generate(prompt: String): ProceduralOutcome {
        val first = generator.complete(SYSTEM_PROMPT, firstUserPrompt(prompt))
        return when (val firstResult = evaluate(first)) {
            is AttemptResult.Ok -> firstResult.validated.toOutcome(retried = false)
            is AttemptResult.Bad -> {
                val retry = generator.complete(
                    SYSTEM_PROMPT,
                    retryUserPrompt(prompt, firstResult.reasons, firstResult.rawText),
                )
                when (val retryResult = evaluate(retry)) {
                    is AttemptResult.Ok -> retryResult.validated.toOutcome(retried = true)
                    is AttemptResult.Bad -> ProceduralOutcome.Failure(
                        reasons = retryResult.reasons,
                        lastRawOutputText = retryResult.rawText ?: firstResult.rawText,
                    )
                }
            }
        }
    }

    private fun evaluate(completion: String): AttemptResult {
        val block = extractFence(completion)
            ?: return AttemptResult.Bad(
                reasons = listOf("no fenced ```object3d``` block found in the model's reply"),
                rawText = completion.trim().takeIf { it.isNotEmpty() },
            )
        return if (block.startsWith("{")) validateDsl(block) else validateObj(block)
    }

    private fun validateDsl(text: String): AttemptResult {
        val scene: ProceduralScene = try {
            ProceduralSceneCodec.fromJsonString(text)
        } catch (e: Exception) {
            return AttemptResult.Bad(
                reasons = listOf("the ```object3d``` block looked like JSON but failed to parse: ${e.message}"),
                rawText = text,
            )
        }
        return when (val v = ProceduralSceneValidator.validate(scene)) {
            is ProceduralSceneValidation.Valid -> AttemptResult.Ok(Validated.Dsl(v.scene, text))
            is ProceduralSceneValidation.Invalid -> AttemptResult.Bad(v.reasons, text)
        }
    }

    private fun validateObj(text: String): AttemptResult =
        when (val v = ObjParser.validate(text)) {
            is ObjParser.ObjValidation.Valid -> AttemptResult.Ok(Validated.RawObj(text, v.stats))
            is ObjParser.ObjValidation.Invalid -> AttemptResult.Bad(v.reasons, text)
        }

    private sealed class Validated {
        data class Dsl(val scene: ProceduralScene, val rawOutputText: String) : Validated()
        data class RawObj(val objText: String, val stats: ObjParser.ObjStats) : Validated()
    }

    private fun Validated.toOutcome(retried: Boolean): ProceduralOutcome = when (this) {
        is Validated.Dsl -> ProceduralOutcome.Dsl(scene, rawOutputText, retried)
        is Validated.RawObj -> ProceduralOutcome.RawObj(objText, stats, objText, retried)
    }

    private sealed class AttemptResult {
        data class Ok(val validated: Validated) : AttemptResult()
        data class Bad(val reasons: List<String>, val rawText: String?) : AttemptResult()
    }

    companion object {
        /** Matches a fenced ```object3d block first; falls back to ANY fenced block (a model
         *  that forgets the language tag, or tags it e.g. ```json, still gets a fair chance —
         *  the validators below are the real gate, not the fence tag). Non-greedy + DOTALL so a
         *  reply with prose before/after the fence still extracts cleanly. */
        private val OBJECT3D_FENCE = Regex("```object3d\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        private val ANY_FENCE = Regex("```[A-Za-z0-9_-]*\\s*\\n([\\s\\S]*?)```")

        internal fun extractFence(text: String): String? {
            val tagged = OBJECT3D_FENCE.find(text)?.groupValues?.get(1)
            val block = tagged ?: ANY_FENCE.find(text)?.groupValues?.get(1)
            return block?.trim()?.takeIf { it.isNotEmpty() }
        }

        private const val SYSTEM_PROMPT = """You generate 3D objects. Reply with EXACTLY ONE fenced code block tagged ```object3d, containing either:

1. PREFERRED — a compact primitive-DSL JSON scene, this exact shape:
   {"schemaVersion":"1.0.0","ops":[{"id":"box1","kind":"BOX","transform":{"translate":{"x":0,"y":0,"z":0},"rotateDeg":{"x":0,"y":0,"z":0},"scale":{"x":1,"y":1,"z":1}},"colorHex":"#RRGGBB","params":{"width":1,"height":1,"depth":1},"group":null}]}
   - kind is one of BOX, SPHERE, CYLINDER, CONE, TORUS, PLANE.
   - required params by kind: BOX needs width/height/depth; SPHERE needs radius; CYLINDER/CONE need radius/height; TORUS needs radius/tubeRadius; PLANE needs width/height.
   - colorHex is a #RRGGBB or #RRGGBBAA hex color — never a URL or free text.
   - id and group are short identifiers (letters/digits/-/_ only) — never URLs or free text.
2. ACCEPTED — raw Wavefront OBJ text (v/vn/vt/f lines).

Reply with ONLY the fenced block. No prose before or after it."""

        private fun firstUserPrompt(prompt: String): String = "Generate a 3D object: $prompt"

        private fun retryUserPrompt(prompt: String, reasons: List<String>, priorBlock: String?): String = buildString {
            append("Your previous ```object3d``` reply was invalid:\n")
            for (reason in reasons) append("- ").append(reason).append('\n')
            if (priorBlock != null) {
                append("\nYour previous reply was:\n```object3d\n").append(priorBlock).append("\n```\n")
            }
            append("\nFix EVERY issue listed above and reply again with exactly one corrected ```object3d block for: ")
            append(prompt)
        }
    }
}

/**
 * §4's outcome: a validated on-device generation (the primitive DSL or a raw OBJ mesh, either
 * possibly the product of the one allowed retry), or an honest [Failure] carrying the last
 * validator complaint. [rawOutputText] on every variant is exactly what
 * schemas/object3d/object3d-node.schema.json's `GenerationDebugInfo.rawOutputText` documents —
 * the untrusted pre-validation text a caller mints into the node's debug field.
 */
sealed class ProceduralOutcome {

    data class Dsl(
        val scene: ProceduralScene,
        val rawOutputText: String,
        val retried: Boolean,
    ) : ProceduralOutcome()

    data class RawObj(
        val objText: String,
        val stats: ObjParser.ObjStats,
        val rawOutputText: String,
        val retried: Boolean,
    ) : ProceduralOutcome()

    data class Failure(
        val reasons: List<String>,
        val lastRawOutputText: String?,
    ) : ProceduralOutcome()
}
