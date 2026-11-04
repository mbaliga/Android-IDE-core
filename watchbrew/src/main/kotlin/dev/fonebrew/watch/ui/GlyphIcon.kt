package dev.fonebrew.watch.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import dev.aarso.watch.R
import dev.fonebrew.watch.core.Glyph

/** The vector drawable that draws a [Glyph]. One shape per state; tinted at the use site, never hue-coded. */
internal fun Glyph.drawableRes(): Int = when (this) {
    Glyph.TASK_TODO -> R.drawable.glyph_task_todo
    Glyph.TASK_DOING -> R.drawable.glyph_task_doing
    Glyph.TASK_BLOCKED -> R.drawable.glyph_task_blocked
    Glyph.TASK_DONE -> R.drawable.glyph_task_done
    Glyph.LOOP_RUNNING -> R.drawable.glyph_loop_running
    Glyph.LOOP_COMPLETE -> R.drawable.glyph_loop_complete
    Glyph.LOOP_STOPPED -> R.drawable.glyph_loop_stopped
    Glyph.LOOP_FAILED -> R.drawable.glyph_loop_failed
    Glyph.LOOP_UNKNOWN -> R.drawable.glyph_loop_unknown
    Glyph.ON_DEVICE -> R.drawable.glyph_on_device
    Glyph.CLOUD_WATCHED -> R.drawable.glyph_cloud_watched
    Glyph.LINK_CHECKING -> R.drawable.glyph_link_checking
    Glyph.LINK_OK -> R.drawable.glyph_link_ok
    Glyph.LINK_BROKEN -> R.drawable.glyph_link_broken
    Glyph.LINK_MISMATCH -> R.drawable.glyph_link_mismatch
    Glyph.NOTICE -> R.drawable.glyph_notice
}

/**
 * Draws a [Glyph]. `contentDescription` is deliberately null: the glyph always sits beside its word
 * (the label is the accessible name), so announcing the shape too would only be noise.
 */
@Composable
internal fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color = MaterialTheme.colors.onBackground) {
    Icon(painter = painterResource(glyph.drawableRes()), contentDescription = null, modifier = modifier.size(size), tint = tint)
}
