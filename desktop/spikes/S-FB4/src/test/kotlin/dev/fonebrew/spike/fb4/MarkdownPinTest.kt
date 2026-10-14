package dev.fonebrew.spike.fb4

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The app renders every model turn through `com.mikepenz.markdown.m3.Markdown` 0.35.0, which core
 * pins together with Compose Foundation 1.8 (a lower Foundation => NoSuchMethodError on the first
 * markdown turn). This test asks the same question of the desktop pairing: does the pinned renderer
 * compose and draw text on it?
 */
@OptIn(ExperimentalComposeUiApi::class)
class MarkdownPinTest {
    private fun darkPixels(png: ByteArray): Int {
        val img = ImageIO.read(ByteArrayInputStream(png))
        var dark = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            if (((p shr 16) and 0xFF) < 100 && ((p shr 8) and 0xFF) < 100 && (p and 0xFF) < 100) dark++
        }
        return dark
    }

    @Test fun `the pinned markdown renderer composes and draws text on this Compose pairing`() {
        val scene = ImageComposeScene(width = 480, height = 220, density = Density(1f)) {
            MaterialTheme {
                Box(Modifier.background(Color.White).padding(8.dp)) {
                    Markdown(content = "# Heading\n\nBody with **bold** and `code`.\n\n- first\n- second")
                }
            }
        }
        // The renderer parses on a background coroutine and composes the result on a LATER frame, so keep
        // advancing the scene clock (up to 10 s) until text appears instead of judging the first frames.
        var png = ByteArray(0)
        var dark = 0
        // A composition failure (e.g. NoSuchMethodError on a Foundation that lacks BasicText's TextAutoSize
        // overload) is thrown on the scene's recomposer coroutine, NOT into this thread: the scene just
        // draws nothing. Capture it so the failure message names the real cause.
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            var t = 0L
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                png = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
                dark = darkPixels(png)
                if (dark > 300) break
                t += 16_000_000L
                Thread.sleep(16)
            }
        } finally {
            scene.close()
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        File(System.getProperty("spike.outDir")).apply { mkdirs() }.resolve("markdown.png").writeBytes(png)
        println("S-FB4 markdown render: $dark dark (text) pixels")
        val cause = uncaught.firstOrNull()?.let { generateSequence(it) { t -> t.cause }.last() }
        assertTrue(cause == null, "composition failed: $cause")
        assertTrue(dark > 300, "markdown drew almost nothing ($dark dark pixels)")
    }
}
