package dev.aarso.ui.hyle

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import dev.aarso.hyle.Pulse

/**
 * "Heartbeat, not weather" — Hyle's motion rule ([dev.aarso.hyle.Pulse]): a slow, regular,
 * low-amplitude breath that means *alive / connected / watched*, never aperiodic churn. This is
 * the emission behind a [dev.aarso.hyle.Finish.Radiant] surface — apply it to whatever stands in
 * for "a watched, from-elsewhere process is working" (a cloud generation in flight), and leave
 * on-device/local work still ([dev.aarso.hyle.Finish.Reflective]) — it only reflects, it doesn't
 * emit. [pulse] defaults to [Pulse.WATCHED]; pass [Pulse.STILL] (or skip the modifier) for the
 * reflective case.
 */
@Composable
fun Modifier.hylePulse(pulse: Pulse = Pulse.WATCHED): Modifier {
    if (pulse.periodMs <= 1) return this
    val transition = rememberInfiniteTransition(label = "hylePulse")
    val alpha by transition.animateFloat(
        initialValue = pulse.minAlphaPct / 100f,
        targetValue = pulse.maxAlphaPct / 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulse.periodMs / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hylePulseAlpha",
    )
    return this.alpha(alpha)
}
