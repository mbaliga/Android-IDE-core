package dev.fonebrew.domain.remote.term

/** Which mouse events the application asked to receive (DECSET 1000 / 1002 / 1003). */
enum class MouseTracking { NONE, CLICK, DRAG, ANY }

/**
 * The input-affecting modes an application can switch on in the terminal. [VtParser] writes
 * them; the pure key/paste/mouse encoders in `domain/terminal` read them to choose the exact
 * bytes. Plain mutable state: the parser (output thread) writes, the UI thread reads; each field
 * is an independent flag, so `@Volatile` is all the ordering that is needed.
 *
 * The alternate screen and DECTCEM live on [ScreenBuffer] because they change what is drawn.
 */
class TerminalModes {
    /** DECCKM (`CSI ?1h`): cursor keys send `SS3 A` instead of `CSI A`. */
    @Volatile var applicationCursorKeys = false
    /** DECKPAM (`ESC =`) / DECKPNM (`ESC >`): the keypad sends `SS3` sequences. */
    @Volatile var applicationKeypad = false
    /** `CSI ?2004h`: pasted text is wrapped in `ESC[200~ ... ESC[201~`. */
    @Volatile var bracketedPaste = false
    /** `CSI ?1004h`: the terminal reports focus in/out as `CSI I` / `CSI O`. */
    @Volatile var focusReporting = false
    /** 1000 / 1002 / 1003. */
    @Volatile var mouseTracking = MouseTracking.NONE
    /** `CSI ?1006h`: SGR mouse encoding (otherwise the legacy X10 byte encoding). */
    @Volatile var mouseSgr = false

    fun reset() {
        applicationCursorKeys = false
        applicationKeypad = false
        bracketedPaste = false
        focusReporting = false
        mouseTracking = MouseTracking.NONE
        mouseSgr = false
    }
}

/** What the session learned from the stream about itself: title (OSC 0/2) and cwd (OSC 7). */
class TerminalMetadata {
    @Volatile var title: String? = null
    /** Working directory path from OSC 7, as the *shell* reported it. Unknown stays null. */
    @Volatile var workingDirectory: String? = null
    /** Host part of the OSC 7 URL (may be empty/localhost); a remote shell reports its own. */
    @Volatile var workingDirectoryHost: String? = null
}

/** An OSC 52 write the application asked for. The engine NEVER applies it: the host must put it
 *  in front of the user and obtain consent before touching the clipboard. */
data class ClipboardWriteRequest(val selection: String, val text: String)

/** Things the stream tells the host. All default to no-ops. */
interface TerminalEvents {
    fun onTitle(title: String) {}
    fun onWorkingDirectory(path: String, host: String?) {}
    fun onBell() {}
    fun onClipboardWriteRequested(request: ClipboardWriteRequest) {}

    companion object { val NONE: TerminalEvents = object : TerminalEvents {} }
}

/** Where a reply to a terminal query (DA, DSR, window size) goes: the session's pty input. */
fun interface TerminalReplies {
    fun reply(sequence: String)

    companion object { val NONE: TerminalReplies = TerminalReplies { } }
}
