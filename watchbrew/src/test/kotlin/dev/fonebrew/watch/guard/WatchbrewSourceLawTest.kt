package dev.fonebrew.watch.guard

import dev.fonebrew.watch.core.WatchPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level laws: the pure packages stay pure, hue never carries state, and the glyph resources match the vocabulary. */
class WatchbrewSourceLawTest {

    @Test
    fun coreAndRuntime_areFreeOfAndroidAndCompose_soEveryRuleInThemIsJvmTested() {
        for (sub in listOf("core", "runtime")) {
            val files = SourceTree.mainKotlin(sub)
            assertTrue("no sources found in $sub", files.isNotEmpty())
            for (file in files) {
                for (line in SourceTree.codeOnly(file).lines().filter { it.trim().startsWith("import ") }) {
                    val target = line.trim().removePrefix("import ")
                    val allowed = target.startsWith("kotlin.") || target.startsWith("kotlinx.coroutines.") || target.startsWith("java.") ||
                        target.startsWith("dev.fonebrew.watch.core") || target.startsWith("dev.fonebrew.watch.runtime") || target.startsWith("dev.fonebrew.watchbridge")
                    assertTrue("$sub/${file.name}: '$target' is not pure Kotlin/JVM, the bridge, or this module's own pure packages", allowed)
                }
                // The one exception stated, not hidden: core/Surfaces etc. import nothing Android; the check above is the whole rule.
            }
        }
    }

    @Test
    fun thereIsNoSpinnerAnywhere_aMissingPhoneIsAStateNotAnAnimationThatImpliesProgress() {
        // Design doc section 3: phone unreachable is first-class — never a spinner that implies progress.
        val spinner = Regex("""\b(CircularProgressIndicator|LinearProgressIndicator|ProgressIndicator|IndeterminateProgress|ProgressBar|CircularProgress|InfiniteTransition|rememberInfiniteTransition)\b""")
        for (file in SourceTree.mainKotlin()) {
            assertFalse("${file.name} uses a spinner/indeterminate animation", spinner.containsMatchIn(SourceTree.codeOnly(file)))
        }
    }

    @Test
    fun theUiAndSurfaces_chooseColoursOnlyFromThePalette_neverARawHue() {
        // Any literal Color(0x...) / argb literal outside WatchPalette would be a colour the palette law never saw.
        val rawColor = Regex("""Color\(\s*0x[0-9A-Fa-f]+|Color\.(Red|Green|Blue|Yellow|Cyan|Magenta)\b|#[0-9A-Fa-f]{6,8}""")
        for (file in SourceTree.mainKotlin().filter { it.name != "WatchPalette.kt" }) {
            assertFalse("${file.name} has a raw colour literal", rawColor.containsMatchIn(SourceTree.codeOnly(file)))
        }
    }

    @Test
    fun noUiOrPresenterStringNamesAColourToSayWhatAStateIs() {
        val hue = Regex("""["'][^"']*\b(red|green|amber|orange|yellow)\b[^"']*["']""", RegexOption.IGNORE_CASE)
        for (file in SourceTree.mainKotlin()) {
            assertFalse("${file.name} uses a hue word in a string", hue.containsMatchIn(SourceTree.codeOnly(file)))
        }
    }

    @Test
    fun everyGlyphHasAVectorDrawable_andNoOrphanVectorExists() {
        val glyphNames = dev.fonebrew.watch.core.Glyph.entries.map { "glyph_" + it.name.lowercase() + ".xml" }.toSet()
        val onDisk = SourceTree.file("src/main/res/drawable").listFiles()!!.map { it.name }.filter { it.startsWith("glyph_") }.toSet()
        assertEquals(glyphNames, onDisk)
    }

    @Test
    fun theGlyphVectors_areSingleColourMasks_soTheyTintAndNeverCarryAHue() {
        for (file in SourceTree.file("src/main/res/drawable").listFiles()!!.filter { it.name.startsWith("glyph_") }) {
            val colours = Regex("""(?:fillColor|strokeColor)="(#[0-9A-Fa-f]+)"""").findAll(file.readText()).map { it.groupValues[1].uppercase() }.toSet()
            assertTrue("${file.name} colours: $colours", colours.all { it == "#FFFFFFFF" || it == "#00000000" })
        }
    }

    @Test
    fun theWindowBackgroundResource_equalsTheInkToken() {
        val xml = SourceTree.file("src/main/res/values/colors.xml").readText()
        val hex = Regex("""<color name="watch_ink">#([0-9A-Fa-f]{6})</color>""").find(xml)!!.groupValues[1]
        assertEquals(WatchPalette.INK and 0xFFFFFF, hex.toLong(16))
    }
}
