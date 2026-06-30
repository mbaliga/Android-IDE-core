package dev.aarso.domain.model

/**
 * Decides which model a session starts with. Pure so the policy is testable and
 * legible in one place.
 *
 * Order: the persisted choice if it still resolves *and* is on-device; else the
 * first downloaded GGUF; else an echo dev stand-in when one is registered
 * (debug builds only); else null — meaning "no model yet", the explicit state
 * the first-run setup card renders.
 *
 * A persisted cloud model is deliberately not restored (binding rule 2: cloud
 * is opt-in per use, never an ambient default a new session silently resumes).
 */
object DefaultModelPolicy {

    fun resolveActive(specs: List<ModelSpec>, persistedId: String?): ModelSpec? {
        val persisted = persistedId?.let { id -> specs.firstOrNull { it.id == id } }
        if (persisted != null && persisted.isOnDevice) return persisted
        specs.firstOrNull { it.runtime == Runtime.LOCAL_GGUF }?.let { return it }
        return specs.firstOrNull { it.runtime == Runtime.ECHO_DEV }
    }
}
