package dev.fonebrew.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structure guards for the home-screen widgets (docs/widgets.md), read from the files themselves so a
 * later edit can't quietly break a rule the JVM can check: every provider is registered and resizable,
 * nothing polls, every layout is a launcher-styled widget root with no tiny text, the brand comes from
 * app_name, and the widget code reaches no network, key or secret. Rendering itself is owner-verified.
 */
class WidgetResourcesTest {

    private fun moduleDir(): File {
        val dir = listOf(File("."), File("core-engine")).firstOrNull { File(it, "src/main/java/dev/fonebrew/widget").isDirectory }
        assertNotNull("cannot find core-engine from ${File(".").absolutePath}", dir)
        return dir!!
    }

    private fun res(path: String) = File(moduleDir(), "src/main/res/$path")
    private val manifest by lazy { File(moduleDir(), "src/main/AndroidManifest.xml").readText() }

    private fun attr(xml: String, name: String): String? =
        Regex("""android:$name="([^"]*)"""").find(xml)?.groupValues?.get(1)

    private fun receiverBlock(className: String): String {
        val start = manifest.indexOf("android:name=\"$className\"")
        assertTrue("$className is registered in the manifest", start >= 0)
        val open = manifest.lastIndexOf("<receiver", start)
        val close = manifest.indexOf("</receiver>", start)
        return manifest.substring(open, close)
    }

    private fun infoXml(kind: WidgetKind): String {
        val block = receiverBlock(FonebrewWidgets.providerClass(kind).name)
        val resource = Regex("""android:resource="@xml/([a-z_]+)"""").find(block)!!.groupValues[1]
        return res("xml/$resource.xml").readText()
    }

    @Test
    fun everyProvider_isAnExportedAppWidgetReceiver_withItsOwnInfo() {
        for (kind in WidgetKind.entries) {
            val block = receiverBlock(FonebrewWidgets.providerClass(kind).name)
            assertTrue("$kind exported (the launcher binds it)", block.contains("android:exported=\"true\""))
            assertTrue("$kind APPWIDGET_UPDATE", block.contains("android.appwidget.action.APPWIDGET_UPDATE"))
            assertTrue("$kind provider meta-data", block.contains("android.appwidget.provider"))
            assertTrue("$kind label is a string resource", Regex("""android:label="@string/widget_""").containsMatchIn(block))
        }
    }

    @Test
    fun everyInfo_isResizable_neverPolls_andHasPreviewDescriptionAndInitialLayout() {
        for (kind in WidgetKind.entries) {
            val xml = infoXml(kind)
            assertEquals("$kind pushes, never polls", "0", attr(xml, "updatePeriodMillis"))
            assertEquals("$kind resizable both ways", "horizontal|vertical", attr(xml, "resizeMode"))
            assertEquals("home_screen", attr(xml, "widgetCategory"))
            assertTrue("$kind shrinks to 2x1", attr(xml, "minResizeWidth")!!.removeSuffix("dp").toInt() <= 110)
            assertTrue("$kind shrinks to 2x1", attr(xml, "minResizeHeight")!!.removeSuffix("dp").toInt() <= 40)
            listOf("targetCellWidth", "targetCellHeight", "minWidth", "minHeight").forEach { assertNotNull("$kind $it", attr(xml, it)) }
            val preview = attr(xml, "previewLayout")!!.removePrefix("@layout/")
            assertTrue("$kind previewLayout exists", res("layout/$preview.xml").isFile)
            val image = attr(xml, "previewImage")!!.removePrefix("@drawable/")
            assertTrue("$kind previewImage exists", res("drawable/$image.xml").isFile)
            val initial = attr(xml, "initialLayout")!!.removePrefix("@layout/")
            assertTrue("$kind initialLayout exists", res("layout/$initial.xml").isFile)
            val description = attr(xml, "description")!!.removePrefix("@string/")
            assertTrue("$kind description string exists", res("values/strings.xml").readText().contains("name=\"$description\""))
        }
    }

    private fun widgetLayouts(): List<File> =
        res("layout").listFiles { f -> f.name.startsWith("widget_") && f.extension == "xml" }!!.sortedBy { it.name }

    @Test
    fun everyLayout_isAWidgetRoot_withTheSystemBackgroundId() {
        val layouts = widgetLayouts()
        assertTrue("found the widget layouts", layouts.size >= 18)
        for (f in layouts) {
            val text = f.readText()
            val rootStart = text.indexOf('<', text.indexOf("-->").coerceAtLeast(text.indexOf("?>")) + 1)
            val rootTag = text.substring(rootStart, text.indexOf('>', rootStart))
            assertTrue("${f.name}: root id is @android:id/background", rootTag.contains("android:id=\"@android:id/background\""))
            assertTrue("${f.name}: rounded widget background", rootTag.contains("@drawable/widget_bg"))
        }
    }

    @Test
    fun largeLayouts_keepTheirActionOrFooter_belowAWeightedContentBlock() {
        // Content that outgrows the widget is what gets cut, never the explicit action or the counts.
        val anchored = mapOf(
            "widget_card_large.xml" to "card_action",
            "widget_progress_large.xml" to "footer_text",
            "widget_list_large.xml" to "footer_text",
        )
        for ((file, id) in anchored) {
            val text = res("layout/$file").readText()
            assertTrue("$file has a weighted content block", text.contains("android:layout_height=\"0dp\"\n        android:layout_weight=\"1\""))
            assertTrue(
                "$file: $id is a direct child of the root, after the content block",
                Regex("""\n    </LinearLayout>\n    <TextView\n        android:id="@\+id/$id"""").containsMatchIn(text),
            )
        }
    }

    @Test
    fun noTextIsSmallerThan12sp_anywhereInTheWidgets() {
        val sources = widgetLayouts() + res("values/widget_styles.xml")
        val sizes = sources.flatMap { f ->
            Regex("""(?:android:(?:textSize|autoSizeMinTextSize)="|name="android:(?:textSize|autoSizeMinTextSize)">)(\d+)sp""").findAll(f.readText()).map { f.name to it.groupValues[1].toInt() }.toList()
        }
        assertTrue(sizes.isNotEmpty())
        sizes.forEach { (file, sp) -> assertTrue("$file uses ${sp}sp", sp >= 12) }
    }

    @Test
    fun theBrand_comesFromAppName_neverHardCoded() {
        // Layouts and the view builder: everything a widget shows. (The log tag elsewhere is not shown.)
        val files = widgetLayouts() + File(moduleDir(), "src/main/java/dev/fonebrew/widget/WidgetRender.kt")
        // A quoted string naming the brand (style names like @style/FonebrewWidgetText are not shown).
        val brand = Regex(""""(?!@style/)[^"\n]*Fonebrew[^"\n]*"""")
        val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        files.forEach { f ->
            assertFalse("${f.name} hard-codes the brand", brand.containsMatchIn(f.readText().replace(comments, "")))
        }
        assertTrue(res("layout/widget_loading.xml").readText().contains("@string/app_name"))
    }

    @Test
    fun dayAndNight_defineTheSameColours() {
        fun names(path: String) = Regex("""<color name="([a-z_]+)"""").findAll(res(path).readText()).map { it.groupValues[1] }.toSet()
        val day = names("values/widget_colors.xml")
        assertTrue(day.isNotEmpty())
        assertEquals(day, names("values-night/widget_colors.xml"))
    }

    @Test
    fun widgetCode_reachesNoNetworkKeyOrSecret_andAddsNoPermission() {
        val code = File(moduleDir(), "src/main/java/dev/fonebrew/widget").listFiles { f -> f.extension == "kt" }!!
            .joinToString("\n") { it.readText() }
        val forbidden = listOf(
            "okhttp", "java\\.net", "HttpURLConnection", "apiKey", "KeystoreSecret", "\\.token\\(",
            // message text, the agent's typed objective and raw model output (not `android.content.*`)
            "\\.content\\b(?!\\.)", "\\.objective\\b", "rawModelOutput", "\\.notes\\b",
        )
        forbidden.forEach { assertFalse("widget code must not touch `$it`", Regex(it).containsMatchIn(code)) }
        assertFalse("no exact alarms", manifest.contains("SCHEDULE_EXACT_ALARM") || manifest.contains("USE_EXACT_ALARM"))
    }

    @Test
    fun theWidgetStrings_neverCallTheCouncilAMixtureOfExperts() {
        val strings = res("values/strings.xml").readText()
        assertFalse(Regex("""(?i)\bMoE\b|mixture of experts""").containsMatchIn(strings))
    }
}
