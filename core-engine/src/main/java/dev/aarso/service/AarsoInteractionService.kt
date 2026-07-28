package dev.aarso.service

import android.service.voice.VoiceInteractionService

/**
 * Registers Aarso as a device assistant (handoff §7). Once the user selects Aarso
 * as the default digital assistant, the assist gesture summons it over any app —
 * the same framework Gemini runs on (displacing Gemini is acceptable, §7). The
 * actual session work is in [AarsoInteractionSessionService].
 */
class AarsoInteractionService : VoiceInteractionService()
