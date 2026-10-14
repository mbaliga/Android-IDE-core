package dev.fonebrew.spike.fb4

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Opens the room in a REAL Compose Desktop window (AWT + Skiko, software rasteriser) on a virtual X
 * server (`xvfb-run`), screenshots it, and checks the pixels. Skipped (reported as skipped, not passed)
 * where `xvfb-run` is absent unless `-Pfb4.requireXvfb=true`, which CI sets so a missing X server fails.
 */
class XvfbWindowSmokeTest {

    private fun onPath(cmd: String) =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, cmd).canExecute() }

    @Test fun `a real window opens under Xvfb and shows the room`() {
        if (!onPath("xvfb-run")) {
            if (System.getProperty("spike.requireXvfb") == "true") fail("xvfb-run not found but -Pfb4.requireXvfb=true")
            assumeTrue("xvfb-run not installed: real-window smoke skipped", false)
        }
        val out = File(System.getProperty("spike.outDir"), "window-xvfb.png").also { it.parentFile.mkdirs(); it.delete() }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val pb = ProcessBuilder(
            "xvfb-run", "-a", "-s", "-screen 0 1280x1024x24",
            java, "-Djava.awt.headless=false", "-cp", System.getProperty("spike.runtimeClasspath"),
            "dev.fonebrew.spike.fb4.MainKt", "--smoke", out.path,
        ).redirectErrorStream(true)
        pb.environment()["SKIKO_RENDER_API"] = "SOFTWARE" // no GPU/GL on a virtual X server
        val p = pb.start()
        val log = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "window process timed out:\n$log")
        assertEquals(0, p.exitValue(), "window process failed:\n$log")
        assertTrue(out.isFile && out.length() > 1000, "no screenshot written:\n$log")

        val img = ImageIO.read(out)
        println("S-FB4 xvfb window screenshot: ${img.width}x${img.height}")
        assertTrue(img.width >= 400 && img.height >= 400, "window too small: ${img.width}x${img.height}")
        fun near(p: Int, rgb: Int, tol: Int) = listOf(16, 8, 0).all { s -> kotlin.math.abs(((p shr s) and 0xFF) - ((rgb shr s) and 0xFF)) <= tol }
        var ink = 0; var violet = 0; var text = 0; var raised = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            if (near(p, 0x0E0F12, 4)) ink++
            if (near(p, 0x16181D, 4)) raised++
            if (near(p, 0x8E7BFF, 10)) violet++
            if (near(p, 0xECEDEF, 30)) text++
        }
        println("S-FB4 xvfb window pixels: ink=$ink raised=$raised violet=$violet text=$text")
        assertTrue(ink > 5_000, "no Ink background in the real window")
        assertTrue(raised > 5_000, "no card surfaces in the real window")
        assertTrue(violet > 60, "no violet accent in the real window")
        assertTrue(text > 300, "no text in the real window (fonts?)")
    }
}
