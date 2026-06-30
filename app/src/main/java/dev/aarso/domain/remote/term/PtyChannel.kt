package dev.aarso.domain.remote.term

/**
 * Ties a live PTY stream to a [ScreenBuffer] via a [VtParser], and exposes a thin
 * [TerminalRenderer] seam so the Compose terminal view (design-gated, 🎨) can subscribe
 * without this model depending on any UI. Feed it the remote's output chunks; it keeps the
 * screen current and notifies the renderer. Resize flows both to the buffer and (later, in the
 * data layer) to the remote PTY's window size. Pure orchestration; JVM-tested.
 */
class PtyChannel(
    rows: Int = 24,
    cols: Int = 80,
    private var renderer: TerminalRenderer? = null,
) {
    val screen = ScreenBuffer(rows, cols)
    private val parser = VtParser(screen)

    /** Feed decoded terminal output; updates the screen and notifies the renderer. */
    fun onOutput(text: String) {
        parser.feed(text)
        renderer?.onScreenChanged(screen)
    }

    /** Resize the local screen (the data layer also sends the new window size to the remote). */
    fun resize(rows: Int, cols: Int) {
        screen.resize(rows, cols)
        renderer?.onScreenChanged(screen)
    }

    fun attach(renderer: TerminalRenderer) { this.renderer = renderer; renderer.onScreenChanged(screen) }
    fun detach() { renderer = null }
}

/** The seam a Compose terminal surface implements. Kept UI-free so the model stays pure. */
interface TerminalRenderer {
    fun onScreenChanged(screen: ScreenBuffer)
}
