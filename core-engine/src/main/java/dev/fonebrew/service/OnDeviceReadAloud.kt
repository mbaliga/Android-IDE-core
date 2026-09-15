package dev.fonebrew.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.UUID

/**
 * "Read aloud" (TurnActionsSheet — lane A3's honest missing-list update): Android's on-device
 * [TextToSpeech] engine only — CLAUDE.md rule 1 (no telemetry/phoning-home) and binding rule 2
 * (cloud is opt-in, never a hidden fallback) both rule out any networked speech API, and there
 * is no networked TTS wired here to fall back to, mirroring the "on-device or absent, never a
 * network substitute" stance [OnDeviceDictation] already takes for the input side.
 * [dev.fonebrew.domain.markdown.SpeechText] does the actual markdown-to-plain-text work — this
 * class only drives the platform engine with an already-plain string.
 *
 * Availability is reported *honestly*: [TextToSpeech]'s constructor callback is the only signal
 * the platform gives for "is there an engine at all," so [onAvailabilityKnown] fires exactly
 * once, from that callback, and the caller decides what "not available" means for the row (this
 * app's idiom — see [dev.fonebrew.ui.ChatScreen]'s TurnActionsSheet call site — is
 * disabled-with-a-reason, the same shape `ModelRow` already uses for an unrunnable model, never
 * a silently-absent or silently-inert tap).
 *
 * Must be constructed and used from the main thread — a [TextToSpeech] requirement, same as
 * [OnDeviceDictation]'s [android.speech.SpeechRecognizer]. Not JVM-tested: like
 * [OnDeviceDictation], it wraps a real platform engine this build environment has no device to
 * run (CLAUDE.md "Environment honesty") — [dev.fonebrew.domain.markdown.SpeechText], the pure
 * text transform this class hands to [speak], carries the exhaustive tests instead.
 */
class OnDeviceReadAloud(
    context: Context,
    onAvailabilityKnown: (Boolean) -> Unit = {},
) {

    private var engine: TextToSpeech?
    private var available = false
    private var pendingSpeak: (() -> Unit)? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            available = status == TextToSpeech.SUCCESS
            onAvailabilityKnown(available)
            if (available) {
                pendingSpeak?.invoke()
                pendingSpeak = null
            } else {
                pendingSpeak = null
            }
        }
    }

    /** Whether the engine finished initializing successfully. `false` both before the
     *  constructor callback lands and if it reported failure — [speak] treats both the same
     *  way (queue-until-ready, or graceful no-op via [onDone] if it never arrives). */
    fun isAvailable(): Boolean = available

    /**
     * Speaks [text] (already speech-plain — see [dev.fonebrew.domain.markdown.SpeechText.plain]),
     * replacing anything currently speaking through this instance. [onDone] fires exactly once —
     * on natural completion, an explicit [stop], or engine error — so a caller can reliably clear
     * its own "currently speaking" UI state without a separate timeout. If the engine hasn't
     * finished initializing yet, the call is queued and runs the moment it does; if init already
     * failed, [onDone] fires immediately and nothing is spoken (never a silent hang).
     */
    fun speak(text: String, onDone: () -> Unit) {
        val e = engine
        if (e == null) {
            onDone()
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        e.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = onDone()

                @Deprecated("Deprecated in the platform API; the Int-code overload below is what actually fires on modern Android, this one stays only to satisfy the abstract class.")
                override fun onError(utteranceId: String?) = onDone()
                override fun onError(utteranceId: String?, errorCode: Int) = onDone()
                override fun onStop(utteranceId: String?, interrupted: Boolean) = onDone()
            },
        )
        val runSpeak = {
            e.stop()
            e.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
        if (available) {
            runSpeak()
        } else if (engine != null) {
            pendingSpeak = runSpeak
        } else {
            onDone()
        }
    }

    /** Stops speaking immediately — the sheet's visible stop affordance, and its own dispose. */
    fun stop() {
        engine?.stop()
        pendingSpeak = null
    }

    /** Releases the platform engine. Idempotent; safe to call more than once (e.g. once from the
     *  owning screen's dispose and, defensively, again if the screen is recreated quickly). */
    fun destroy() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
