package dev.fonebrew.spike.fb4

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO

/**
 * Opens the room in a real Compose Desktop window (needs a display; under CI/headless use xvfb-run).
 * `--smoke <out.png>`: after 2.5 s grab the window's pixels from the screen with java.awt.Robot, write a
 * PNG, and exit -- the container/CI evidence that a REAL window (not just an offscreen scene) opened.
 */
fun main(args: Array<String>) {
    val smokeOut = args.indexOf("--smoke").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "S-FB4 room spike",
            state = rememberWindowState(size = DpSize(520.dp, 560.dp)),
        ) {
            ConversationsRoom()
            if (smokeOut != null) {
                LaunchedEffect(Unit) {
                    delay(2500)
                    val shot = Robot().createScreenCapture(Rectangle(window.locationOnScreen, window.size))
                    ImageIO.write(shot, "png", File(smokeOut))
                    exitApplication()
                }
            }
        }
    }
}
