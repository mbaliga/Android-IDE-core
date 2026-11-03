package dev.fonebrew.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Push-to-talk dictation, **on-device recognizer only** (CLAUDE.md rule 1: no telemetry, no
 * phoning home — the networked [SpeechRecognizer] sends audio off-device, so this never falls
 * back to it; where on-device recognition isn't available, voice is simply absent — see
 * `docs/design/voice-input.md`). The mic opens on an explicit [start] and closes on [stop]; there
 * is no ambient listening.
 *
 * Must be constructed and used from the main thread — a [SpeechRecognizer] requirement.
 */
class OnDeviceDictation(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    /** @return false without starting anything if on-device recognition isn't available here. */
    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (!isAvailable(context)) {
            onError("On-device voice recognition isn't available on this phone.")
            return false
        }
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(
            object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    onFinal(results?.firstTranscript().orEmpty())
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.firstTranscript()?.let(onPartial)
                }

                override fun onError(error: Int) {
                    onError(errorMessage(error))
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            },
        )
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            },
        )
        return true
    }

    /** Ends capture and yields whatever was heard via the [start] callbacks — not a cancel. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun Bundle.firstTranscript(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object {
        fun isAvailable(context: Context): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        private fun errorMessage(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
            SpeechRecognizer.ERROR_AUDIO -> "Microphone error."
            SpeechRecognizer.ERROR_CLIENT -> "Cancelled."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Network error (unexpected for on-device recognition)."
            SpeechRecognizer.ERROR_SERVER -> "Recognizer error."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — try again."
            else -> "Voice recognition error ($error)."
        }
    }
}
