package dev.aarso.domain.loop

import dev.aarso.domain.bpmn.BpmnArchive
import dev.aarso.domain.council.PatternLibrary
import dev.aarso.domain.council.ReferencePattern

/**
 * Bridges the curated reference patterns (`domain/council/PatternLibrary`) into the
 * Loops world — the *floor* of loop distillation (docs/design/loop-distillation.md).
 * Each pattern becomes an **Unused** [Loop] whose [Loop.bpmnXml] is the pattern's BPMN
 * (its provenance — source/summary — already rides in the BPMN's start-event
 * `<aarso:meta>`). These are the seed loops the user can duplicate-to-edit.
 *
 * Pure Kotlin; JVM-tested.
 */
object LoopCatalog {

    fun fromPattern(pattern: ReferencePattern, id: String = "loop_${pattern.id}", now: Long = 0L): Loop =
        Loop(
            id = id,
            name = pattern.title,
            bpmnXml = BpmnArchive.write(pattern.graph),
            state = LoopState.UNUSED,
            createdAt = now,
            updatedAt = now,
        )

    /** The whole curated library (MoA, self-consistency, reflexion, debate) as seed loops. */
    fun reference(now: Long = 0L): List<Loop> = PatternLibrary.all.map { fromPattern(it, now = now) }
}
