package dev.fonebrew.service

import android.service.voice.VoiceInteractionService

/**
 * Registers Fonebrew as a device assistant (handoff §7). Once the user selects Fonebrew
 * as the default digital assistant, the assist gesture summons it over any app —
 * the same framework Gemini runs on (displacing Gemini is acceptable, §7). The
 * actual session work is in [FonebrewInteractionSessionService].
 */
class FonebrewInteractionService : VoiceInteractionService()
