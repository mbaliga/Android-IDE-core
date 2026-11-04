package dev.fonebrew.domain.loop

/**
 * Wires [LoopCatalog]'s seed loops into the on-device Loop list exactly once. The trigger is a
 * persisted "seeded, ever" flag, not "the list is currently empty" -- a user who deletes every
 * seeded loop must not have them silently reappear. Callers (SessionStore's flag, LoopStore's
 * save) are passed in as plain functions so this stays pure Kotlin and JVM-tested; the Context-
 * bound wiring lives in AppContainer.
 */
object LoopCatalogSeeder {

    fun seedIfNeeded(
        alreadySeeded: Boolean,
        markSeeded: () -> Unit,
        save: (Loop) -> Unit,
        now: Long = System.currentTimeMillis(),
    ) {
        if (alreadySeeded) return
        LoopCatalog.reference(now).forEach(save)
        markSeeded()
    }
}
