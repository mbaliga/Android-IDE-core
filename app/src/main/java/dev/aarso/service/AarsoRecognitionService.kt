package dev.aarso.service

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Required stub: a VoiceInteractionService must name a RecognitionService. Aarso
 * does no speech recognition (it's summoned by gesture, not wake-word), so this
 * just errors out cleanly if anything tries to listen.
 */
class AarsoRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
