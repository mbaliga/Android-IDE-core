package dev.fonebrew.spike.fb4

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S-FB4 evidence: the room is drawn by Compose Desktop's own pipeline into an offscreen Skia
 * surface (ImageComposeScene) -- no X server, no GPU. This is a render of the SPIKE's simplified
 * copy; it says nothing about how the Android app's real ChatsRoom looks.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RoomRenderTest {

    private val w = 520
    private val h = 560

    private fun render(selectedTab: Int = 0, conversations: List<ConversationSummary> = SampleConversations): ByteArray {
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) {
            ConversationsRoom(conversations = conversations, selectedTab = selectedTab)
        }
        try {
            // two frames so layout + draw settle
            scene.render(0L)
            val image = scene.render(16_000_000L)
            return image.encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
    }

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))

    private fun BufferedImage.near(x: Int, y: Int, rgb: Int, tol: Int = 6): Boolean {
        val p = getRGB(x, y)
        return listOf(16, 8, 0).all { s -> kotlin.math.abs(((p shr s) and 0xFF) - ((rgb shr s) and 0xFF)) <= tol }
    }

    private fun BufferedImage.count(pred: (Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until height) for (x in 0 until width) if (pred(getRGB(x, y))) n++
        return n
    }

    private fun close(p: Int, rgb: Int, tol: Int = 24) = listOf(16, 8, 0).all { s -> kotlin.math.abs(((p shr s) and 0xFF) - ((rgb shr s) and 0xFF)) <= tol }

    @Test fun `the room renders to a non-blank image of the requested size`() {
        val png = render()
        val img = decode(png)
        File(System.getProperty("spike.outDir")).apply { mkdirs() }.resolve("room-default.png").writeBytes(png)
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        assertTrue(img.near(2, 2, 0x0E0F12), "background is not the Ink surface")
        val distinct = HashSet<Int>().also { s -> for (y in 0 until h step 3) for (x in 0 until w step 3) s += img.getRGB(x, y) }
        assertTrue(distinct.size > 25, "image looks flat: only ${distinct.size} distinct colours")
    }

    @Test fun `palette elements are present - cards, violet accent, text`() {
        val img = decode(render())
        assertTrue(img.count { close(it, 0x16181D, 4) } > 20_000, "no Raised card surface drawn")
        assertTrue(img.count { close(it, 0x8E7BFF, 10) } > 60, "no violet accent (tab bar / tree marker) drawn")
        assertTrue(img.count { close(it, 0xECEDEF, 30) } > 400, "no high-contrast text drawn (fonts missing?)")
    }

    @Test fun `both on-device and cloud-watched markers are drawn (glyph + label) and the flag changes the render`() {
        val img = decode(render())
        // both glyph tints exist (cyan square for ON-DEVICE, amber eye for CLOUD)...
        assertTrue(img.count { close(it, 0x08FED5, 40) } > 8, "no ON-DEVICE glyph pixels")
        assertTrue(img.count { close(it, 0xF78819, 40) } > 8, "no CLOUD/watched glyph pixels")
        // ...and removing the labels' text changes the picture too (the label is drawn, not just the tint)
        val labelled = render()
        val cloudOnly = render(conversations = SampleConversations.map { it.copy(cloud = true) })
        assertFalse(labelled.contentEquals(cloudOnly), "cloud flag did not change the render")
    }

    @Test fun `state changes change the pixels - selected tab and starred flag`() {
        val tab0 = render(selectedTab = 0)
        val tab3 = render(selectedTab = 3)
        assertFalse(tab0.contentEquals(tab3), "selected tab not reflected in render")
        val star = render()
        val noStar = render(conversations = SampleConversations.map { it.copy(starred = false) })
        assertFalse(star.contentEquals(noStar), "starred flag not reflected in render")
    }

    @Test fun `rendering is deterministic`() {
        assertContentEquals(render(), render())
    }

    @Test fun `FINDING - report the toolchain pairing that actually ran`() {
        val kotlin = KotlinVersion.CURRENT
        val cp = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).name }
        val interesting = cp.filter { Regex("^(skiko|ui-desktop|runtime-desktop|foundation-desktop|material3-desktop|foundation-layout-desktop|kotlin-stdlib)").containsMatchIn(it) }.sorted()
        println("S-FB4 pairing: kotlinVersion(gradle)=${System.getProperty("spike.kotlinVersion")} composePlugin=${System.getProperty("spike.composeVersion")}")
        println("S-FB4 running Kotlin stdlib=$kotlin")
        println("S-FB4 classpath: $interesting")
        assertEquals(System.getProperty("spike.kotlinVersion").substringBefore('-').split('.').take(2), listOf(kotlin.major.toString(), kotlin.minor.toString()))
    }
}
