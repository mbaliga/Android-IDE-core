package dev.fonebrew.watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.fonebrew.watch.core.Destination
import dev.fonebrew.watch.ui.WatchNav
import dev.fonebrew.watch.ui.WatchTheme

/**
 * The one Activity. Opened from the launcher, from the Tile (Ask) or from the complication (Tasks);
 * the destination extra is untrusted input and goes through [Destination.fromExtra]. On every resume
 * it re-checks the phone link and pulls fresh hello/tasks/loops (cheap; the phone answers with the
 * contract's own messages).
 */
internal class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = Destination.fromExtra(intent?.getStringExtra(EXTRA_DESTINATION))
        val controller = watchRuntime.controller
        setContent { WatchTheme { WatchNav(controller, start) } }
    }

    override fun onResume() {
        super.onResume()
        watchRuntime.controller.refresh()
    }

    companion object {
        const val EXTRA_DESTINATION = "destination"
        const val DEST_ASK = "ask"
        const val DEST_TASKS = "tasks"
    }
}
