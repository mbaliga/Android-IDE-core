package dev.fonebrew.watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.fonebrew.watch.core.Destination
import dev.fonebrew.watch.runtime.WatchController

/** Home, Ask, Tasks, Loops — swipe right to go back. */
@Composable
internal fun WatchNav(controller: WatchController, start: Destination) {
    val nav: NavHostController = rememberSwipeDismissableNavController()
    val state by controller.state.collectAsState()

    SwipeDismissableNavHost(navController = nav, startDestination = start.route) {
        composable(Destination.HOME.route) {
            HomeScreen(
                state = state,
                onAsk = { nav.navigate(Destination.ASK.route) },
                onTasks = { nav.navigate(Destination.TASKS.route) },
                onLoops = { nav.navigate(Destination.LOOPS.route) },
                onRefresh = controller::refresh,
            )
        }
        composable(Destination.ASK.route) { AskScreen(state, controller) }
        composable(Destination.TASKS.route) { TasksScreen(state, controller) }
        composable(Destination.LOOPS.route) { LoopsScreen(state, controller) }
    }
}
