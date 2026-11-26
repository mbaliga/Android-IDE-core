package dev.fonebrew.ui.layout

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.fonebrew.domain.layout.DisplayState
import dev.fonebrew.domain.layout.ScreenClass

/**
 * The resolved [ScreenClass] for the current window. **Available, not acted on**: no shell reads
 * it yet (the desktop shell is a later package). PHONE when no provider is above (previews, tests).
 */
val LocalScreenClass = compositionLocalOf { ScreenClass.PHONE }

/** The full display snapshot (displays, input presence, arrangement), or null without a provider. */
val LocalDisplayState = compositionLocalOf<DisplayState?> { null }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Starts a [dev.fonebrew.domain.layout.DisplayEnvironment] for the hosting activity and provides
 * [LocalScreenClass] / [LocalDisplayState]. Draws nothing and changes no layout. Because
 * MainActivity now handles `screenSize|density|orientation|...` itself instead of restarting, a
 * configuration change shows up as a new `LocalConfiguration`, which triggers a refresh.
 */
@Composable
fun ProvideDisplayEnvironment(content: @Composable () -> Unit) {
    val activity = LocalContext.current.findActivity()
    if (activity == null) {
        content()
        return
    }
    val env = remember(activity) { AndroidDisplayEnvironment.create(activity) }
    DisposableEffect(env) {
        env.start()
        onDispose { env.stop() }
    }
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration) { env.refresh() }
    val state by env.state.collectAsState()
    CompositionLocalProvider(LocalDisplayState provides state, LocalScreenClass provides state.screenClass) {
        content()
    }
}
