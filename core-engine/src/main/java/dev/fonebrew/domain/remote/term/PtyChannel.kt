package dev.fonebrew.domain.remote.term

import dev.fonebrew.domain.terminal.Utf8StreamDecoder

/**
 * Ties a live PTY stream to a [ScreenBuffer] via a [VtParser], and exposes a thin
 * [TerminalRenderer] seam so the Compose terminal view can subscribe without this model
 * depending on any UI. Feed it the remote's output (bytes, via a stateful UTF-8 decoder, or
 * already-decoded text); it keeps the screen current and notifies the renderer. Pure
 * orchestration; JVM-tested.
 *
 * [modes] and [meta] are the live input modes / title / cwd the stream has set; [events] and
 * [replies] are the host's wiring (both may be set after construction, and stay null-safe).
 * Parsing and snapshotting share the screen's monitor, so a UI thread can read while the reader
 * thread feeds.
 */
class PtyChannel(
    rows: Int = 24,
    cols: Int = 80,
    private var renderer: TerminalRenderer? = null,
    maxScrollback: Int = ScreenBuffer.DEFAULT_SCROLLBACK,
) {
    val screen = ScreenBuffer(rows, cols, maxScrollback)
    val modes = TerminalModes()
    val meta = TerminalMetadata()

    /** Host callbacks for title / cwd / bell / OSC 52 write requests. */
    @Volatile var events: TerminalEvents? = null
    /** Where query replies (DA, DSR, size) go - the session's pty input. Null = stay silent. */
    @Volatile var replies: TerminalReplies? = null

    private val decoder = Utf8StreamDecoder()
    private val parser = VtParser(
        screen, modes, meta,
        events = object : TerminalEvents {
            override fun onTitle(title: String) { events?.onTitle(title) }
            override fun onWorkingDirectory(path: String, host: String?) { events?.onWorkingDirectory(path, host) }
            override fun onBell() { events?.onBell() }
            override fun onClipboardWriteRequested(request: ClipboardWriteRequest) {
                events?.onClipboardWriteRequested(request)
            }
        },
        replies = TerminalReplies { seq -> replies?.reply(seq) },
    )

    /** Feed decoded terminal output; updates the screen and notifies the renderer. */
    fun onOutput(text: String) {
        synchronized(screen) { parser.feed(text) }
        renderer?.onScreenChanged(screen)
    }

    /** Feed raw pty bytes: decoded statefully, so a multibyte character split across reads is
     *  reassembled rather than corrupted. */
    fun onOutputBytes(bytes: ByteArray, length: Int = bytes.size) {
        synchronized(screen) { parser.feed(decoder.decode(bytes, 0, length)) }
        renderer?.onScreenChanged(screen)
    }

    /** The stream ended: an incomplete trailing character becomes U+FFFD rather than being held forever. */
    fun flushDecoder() {
        val tail = decoder.finish()
        if (tail.isNotEmpty()) onOutput(tail)
    }

    /** Resize the local screen (the data layer also sends the new window size to the remote). */
    fun resize(rows: Int, cols: Int) {
        screen.resize(rows, cols)
        renderer?.onScreenChanged(screen)
    }

    /** Full reset (grid, scrollback, cursor, modes) - for switching to a different machine,
     *  where the previous session's output shouldn't linger. See [ScreenBuffer.resetAll]. */
    fun reset() {
        synchronized(screen) {
            screen.resetAll()
            modes.reset()
            meta.title = null
            meta.workingDirectory = null
            meta.workingDirectoryHost = null
            decoder.reset()
        }
        renderer?.onScreenChanged(screen)
    }

    fun attach(renderer: TerminalRenderer) { this.renderer = renderer; renderer.onScreenChanged(screen) }
    fun detach() { renderer = null }
}

/** The seam a Compose terminal surface implements. Kept UI-free so the model stays pure. */
interface TerminalRenderer {
    fun onScreenChanged(screen: ScreenBuffer)
}
