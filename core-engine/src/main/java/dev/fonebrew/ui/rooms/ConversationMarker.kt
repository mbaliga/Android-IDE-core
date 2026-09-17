package dev.fonebrew.ui.rooms

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.theme.LocalHyleColors

/**
 * What a row in the conversation list *is*, as one leading glyph.
 *
 * Every state below is read off the message tree or the live generation, never decoration:
 * a conversation cannot show [BRANCHED] unless its tree actually forks, and it cannot
 * breathe unless a watched (cloud) model is generating into it right now.
 */
enum class ConversationMark {
    /** The tree forks — more than one live leaf. Drawn as the branch itself. */
    BRANCHED,

    /** A linear conversation with turns in it. */
    LINEAR,

    /** Opened but empty — no turns yet. Hollow, because there is nothing inside it. */
    EMPTY,
}

/**
 * Hyle's motion rule, applied (`cells/HylePulse.kt`: *"heartbeat, not weather"* — a slow,
 * regular, low-amplitude breath meaning **alive / connected / watched**, never aperiodic
 * churn). The design system splits surfaces into `Radiant` (emits — a watched,
 * from-elsewhere process) and `Reflective` (only reflects — local work), and that split is
 * exactly this app's binding rule 2: **cloud is a watched object, on-device is the
 * default**.
 *
 * So the dot breathes *only* while a cloud model is generating into that conversation.
 * On-device generation stays still — not because it is less alive, but because nothing is
 * leaving the phone, and the list should make that difference visible without a word.
 */
enum class ConversationPulse {
    /** Nothing in flight. Still. */
    NONE,

    /** Generating on-device — reflective: solid accent, no breath. */
    LOCAL,

    /** Generating on a watched cloud provider — radiant: it breathes. */
    WATCHED,
}

// Hyle's Pulse.WATCHED, verbatim (cells/HylePulse.kt): 2.4s period, 42%→78% alpha.
private const val PULSE_PERIOD_MS = 2400
private const val PULSE_MIN_ALPHA = 0.42f
private const val PULSE_MAX_ALPHA = 0.78f

/**
 * The leading marker for a conversation row: a filled dot, a hollow ring, or the branch
 * glyph — the same vocabulary the owner's reference list uses, bound here to real state.
 * [accent] carries the user's chosen accent; an unopened row uses the muted ink so the
 * accent means "this is the one you are in", not merely "this is a row".
 */
@Composable
fun ConversationMarker(
    mark: ConversationMark,
    pulse: ConversationPulse,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = LocalHyleColors.current
    val accent = c.violet
    val tint = when {
        active || pulse != ConversationPulse.NONE -> accent
        mark == ConversationMark.EMPTY -> c.textDisabled
        else -> c.textMid
    }

    // Only a watched (cloud) turn breathes. Everything else holds still.
    val alpha = if (pulse == ConversationPulse.WATCHED) {
        val transition = rememberInfiniteTransition(label = "conversationPulse")
        val animated by transition.animateFloat(
            initialValue = PULSE_MIN_ALPHA,
            targetValue = PULSE_MAX_ALPHA,
            animationSpec = infiniteRepeatable(
                animation = tween(PULSE_PERIOD_MS / 2, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "conversationPulseAlpha",
        )
        animated
    } else {
        1f
    }

    val description = when {
        pulse == ConversationPulse.WATCHED -> "Generating on a watched cloud model"
        pulse == ConversationPulse.LOCAL -> "Generating on this device"
        mark == ConversationMark.BRANCHED -> "Branched conversation"
        mark == ConversationMark.EMPTY -> "Empty conversation"
        else -> "Conversation"
    }

    Canvas(
        modifier
            .size(18.dp)
            .semantics { contentDescription = description },
    ) {
        val w = size.width
        val h = size.height
        val col = tint.copy(alpha = alpha)

        when (mark) {
            // Two nodes joined by a stem that splits — the fork drawn as itself, so a
            // glance at the list shows which conversations carry alternatives.
            ConversationMark.BRANCHED -> {
                val sw = w * 0.10f
                val trunkX = w * 0.30f
                val r = w * 0.11f
                drawLine(
                    col,
                    Offset(trunkX, h * 0.22f),
                    Offset(trunkX, h * 0.78f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    col,
                    Offset(trunkX, h * 0.50f),
                    Offset(w * 0.66f, h * 0.50f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
                drawCircle(col, radius = r, center = Offset(trunkX, h * 0.18f), style = Stroke(sw))
                drawCircle(col, radius = r, center = Offset(trunkX, h * 0.82f), style = Stroke(sw))
                drawCircle(col, radius = r, center = Offset(w * 0.74f, h * 0.50f), style = Stroke(sw))
            }
            // Solid: it has something in it.
            ConversationMark.LINEAR -> drawCircle(col, radius = w * 0.20f, center = Offset(w / 2f, h / 2f))
            // Hollow: opened, but nothing inside yet.
            ConversationMark.EMPTY -> drawCircle(
                col,
                radius = w * 0.18f,
                center = Offset(w / 2f, h / 2f),
                style = Stroke(w * 0.09f),
            )
        }
    }
}
