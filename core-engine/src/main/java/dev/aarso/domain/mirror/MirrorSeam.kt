package dev.aarso.domain.mirror

/**
 * The single point where a host installs the mirror lens. Defaults to [InertMirrorLens]
 * so the app ships honest and blank; a future, Issue-#2-gated lens is installed by
 * swapping the held instance — the rest of the app keeps calling [lens] through the
 * [MirrorLens] interface and never knows the difference.
 *
 * This is the *whole* of the seam: a held reference + a swap. No metric, no baseline,
 * no persistence. Pure; JVM-tested.
 */
class MirrorSeam(initial: MirrorLens = InertMirrorLens) {

    var lens: MirrorLens = initial
        private set

    /** Install a real lens (later, post Issue #2). Idempotent; replaces whatever is held. */
    fun install(lens: MirrorLens) {
        this.lens = lens
    }

    /** Drop back to the inert default. */
    fun uninstall() {
        this.lens = InertMirrorLens
    }

    /** Whether a real (non-inert) lens is currently installed. */
    val active: Boolean get() = lens.installed
}
