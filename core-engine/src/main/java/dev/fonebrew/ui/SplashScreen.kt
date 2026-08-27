package dev.fonebrew.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import dev.aarso.hyle.theme.LocalHyleColors
import dev.fonebrew.core_engine.R
import kotlinx.coroutines.delay

/** How long the brand ceremony holds before handing off to onboarding/home. A flat
 *  delay, not a loading gate — it exists to be read, not to hide startup work, so a
 *  slow cold start can never make it linger. */
private const val SPLASH_DURATION_MS = 900L

// Width fractions + gap weights measured directly off the owner's splash mockup
// (2026-08-27, "Mockup splash black/white.png" — pixel-measured, not eyeballed: the
// glyph's own true max-width was 34.79% of the mockup's screen width, and the three
// vertical gaps were 2.12% / 22.32% / 8.13% of full screen height) rather than
// guessed, then kept as FRACTIONS/WEIGHTS (not copied dp) so the layout holds its
// proportions on any phone aspect ratio, not just the mockup's own canvas.
//
// The glyph asset itself matters here too: it's cropped from THIS splash mockup
// specifically, not rescaled from the square "FB on Black/White" app-icon export —
// the two are genuinely different art (the icon export's lanyard loop is drawn wider
// than its own glass; here the loop is drawn narrower than the glass), confirmed by
// measuring both rather than assuming a uniform rescale. Cropping from the wrong
// source would have made this width fraction alone false to the mockup.
private const val GLYPH_WIDTH_FRACTION = 0.3479f
private const val WORDMARK_WIDTH_FRACTION = 0.6659f
private const val GAP_TOP_WEIGHT = 0.0212f
private const val GAP_MIDDLE_WEIGHT = 0.2232f
private const val GAP_BOTTOM_WEIGHT = 0.0813f

/**
 * The brand ceremony shown once per cold start — full-bleed ground, the FoneBrew
 * glass-on-a-lanyard mark, the wordmark pinned low — reproducing the owner's splash
 * mockup. [dark] selects the asset pair: white-outlined glyph + white "FONE" on the
 * near-black [dev.aarso.hyle.theme.HyleColors.ink], vs. black-outlined glyph + black
 * "FONE" on the light ink. Both wordmark variants keep "BREW" the same orange either
 * way — that part of the mark never changes with theme.
 *
 * The glyph and wordmark are shipped as separate alpha-transparent PNGs (extracted
 * from the delivered "FB on Black"/"FB on White" flat exports by difference-matting
 * the two backgrounds apart — those exports had no alpha channel of their own), not
 * as the single flattened splash mockup image: that mockup draws a phone-bezel frame
 * around the content for presentation, which would render as a phone-inside-a-phone
 * if shipped as-is.
 *
 * Real-device feel — timing, whether 900ms reads right, the exact vertical rhythm on
 * an actual screen — is owner-verified; this container has no device.
 */
@Composable
fun FonebrewSplash(dark: Boolean, onFinished: () -> Unit) {
    val c = LocalHyleColors.current
    LaunchedEffect(Unit) {
        delay(SPLASH_DURATION_MS)
        onFinished()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.ink)
            .windowInsetsPadding(WindowInsets.systemBars),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(GAP_TOP_WEIGHT))
        Image(
            painter = painterResource(if (dark) R.drawable.splash_glyph_dark else R.drawable.splash_glyph_light),
            // Decorative — the wordmark right below carries the accessible label.
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(GLYPH_WIDTH_FRACTION),
            contentScale = ContentScale.FillWidth,
        )
        Spacer(Modifier.weight(GAP_MIDDLE_WEIGHT))
        Image(
            painter = painterResource(if (dark) R.drawable.splash_wordmark_dark else R.drawable.splash_wordmark_light),
            contentDescription = "FoneBrew",
            modifier = Modifier.fillMaxWidth(WORDMARK_WIDTH_FRACTION),
            contentScale = ContentScale.FillWidth,
        )
        Spacer(Modifier.weight(GAP_BOTTOM_WEIGHT))
    }
}
