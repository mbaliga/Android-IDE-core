package dev.fonebrew.watch.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The design doc's hard lines for the watch app, enforced by the gate rather than by good intentions
 * (docs/design/watchbrew.md sections 1 to 3). Each test names the rule it guards; none of them can
 * be satisfied by editing a comment.
 */
class WatchbrewModuleGuardTest {

    private val build get() = SourceTree.codeOnly(SourceTree.file("build.gradle.kts"))

    // ---- dependency law ----

    @Test
    fun theOnlyProjectDependencyIsWatchBridge_neverCoreEngine_neverTheApp_neverHyle() {
        val projectDeps = Regex("""project\(\s*"([^"]+)"\s*\)""").findAll(build).map { it.groupValues[1] }.toList()
        assertEquals(listOf(":watch-bridge"), projectDeps)
        for (forbidden in listOf("core-engine", "core_engine", "dev.aarso:hyle", "hyle", "crash-recovery", "interaction-mode", "sdengine", ":app")) {
            assertFalse("build.gradle.kts must not mention '$forbidden'", build.contains(forbidden))
        }
    }

    @Test
    fun noSourceImportsTheEngineOrPhoneHyle() {
        for (file in SourceTree.mainKotlin()) {
            for (line in SourceTree.codeOnly(file).lines().filter { it.trim().startsWith("import ") }) {
                val target = line.trim().removePrefix("import ")
                assertFalse("${file.name}: $target", target.startsWith("dev.fonebrew.core_engine") || target.startsWith("dev.fonebrew.data") ||
                    target.startsWith("dev.fonebrew.domain") || target.startsWith("dev.fonebrew.inference") || target.startsWith("dev.aarso.hyle") ||
                    target.startsWith("androidx.compose.material3"))
            }
        }
    }

    @Test
    fun noNetworkOrAnalyticsLibraryIsDeclared() {
        val deps = build.substringAfter("dependencies {")
        for (bad in listOf("okhttp", "retrofit", "ktor", "volley", "firebase", "crashlytics", "analytics", "measurement", "sentry", "play-services-ads", "mlkit", "coil", "glide")) {
            assertFalse("a '$bad' dependency would put a network or telemetry path on the wrist", deps.contains(bad, ignoreCase = true))
        }
    }

    @Test
    fun theOnlyGmsArtifactIsTheWearableDataLayer_resolvedThroughTheCatalog() {
        val coordinates = SourceTree.declaredCoordinates(build)
        assertTrue("expected to resolve real coordinates, got $coordinates", coordinates.size > 10)
        val gms = coordinates.filter { it.startsWith("com.google.android.gms:") }.toSet()
        assertEquals(setOf("com.google.android.gms:play-services-wearable"), gms)
    }

    @Test
    fun everyDeclaredCoordinateIsAndroidxKotlinxOrTheWearableLayer() {
        val allowedGroups = listOf("androidx.", "org.jetbrains.kotlinx:", "org.json:", "junit:", "com.google.android.gms:play-services-wearable")
        for (coordinate in SourceTree.declaredCoordinates(build)) {
            assertTrue("unexpected dependency $coordinate on the wrist", allowedGroups.any { coordinate.startsWith(it) })
        }
    }

    // ---- no network from the watch ----

    @Test
    fun noSourceTouchesTheNetworkOrACredential() {
        val forbidden = listOf(
            "java.net.", "javax.net.", "okhttp3", "HttpURLConnection", "HttpsURLConnection", "URL(", "Socket(", "WebView",
            "android.net.", "ConnectivityManager", "DownloadManager",
            "KeyStore", "Keystore", "apiKey", "api_key", "API_KEY", "Authorization", "Bearer", "password", "secret",
        )
        for (file in SourceTree.mainKotlin()) {
            val code = SourceTree.codeOnly(file)
            for (bad in forbidden) assertFalse("${file.name} mentions '$bad'", code.contains(bad))
        }
    }

    // ---- manifest ----

    private val manifest: Element by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.newDocumentBuilder().parse(SourceTree.file("src/main/AndroidManifest.xml")).documentElement
    }

    private val ANDROID = "http://schemas.android.com/apk/res/android"

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }

    private fun Element.attr(name: String): String? = getAttributeNS(ANDROID, name).takeIf { it.isNotEmpty() }

    private val application: Element get() = manifest.children("application").single()

    @Test
    fun theManifestRequestsNoPermissionsAtAll_inParticularNotInternet() {
        assertTrue("no <uses-permission> of any kind", manifest.children("uses-permission").isEmpty())
        assertTrue(manifest.children("uses-permission-sdk-23").isEmpty())
        val withoutComments = SourceTree.file("src/main/AndroidManifest.xml").readText().replace(Regex("<!--[\\s\\S]*?-->"), "")
        assertFalse(withoutComments.contains("INTERNET"))
        assertFalse(withoutComments.contains("ACCESS_NETWORK_STATE"))
        assertNull(application.attr("usesCleartextTraffic"))
    }

    @Test
    fun itIsACompanionApp_standaloneFalse_andAWatch() {
        val meta = application.children("meta-data").single { it.attr("name") == "com.google.android.wearable.standalone" }
        assertEquals("false", meta.attr("value"))
        assertTrue(manifest.children("uses-feature").any { it.attr("name") == "android.hardware.type.watch" })
    }

    @Test
    fun backupIsOff_theLastKnownCacheNeverLeavesTheWatch() {
        assertEquals("false", application.attr("allowBackup"))
    }

    @Test
    fun theListenerIsFilteredToTheWatchBrewPrefixOnly() {
        val listener = application.children("service").single { it.attr("name")!!.endsWith("WatchListenerService") }
        val filters = listener.children("intent-filter")
        assertEquals(2, filters.size)
        for (filter in filters) {
            val data = filter.children("data").single()
            assertEquals("/watchbrew/", data.attr("pathPrefix"))
            assertEquals("wear", data.attr("scheme"))
        }
        val actions = filters.flatMap { it.children("action") }.map { it.attr("name") }.toSet()
        assertEquals(setOf("com.google.android.gms.wearable.MESSAGE_RECEIVED", "com.google.android.gms.wearable.CHANNEL_EVENT"), actions)
    }

    @Test
    fun theTileAndComplication_areBoundOnlyBySystemPermissions_andDeclareTheirShape() {
        val tile = application.children("service").single { it.attr("name")!!.endsWith("WatchTileService") }
        assertEquals("com.google.android.wearable.permission.BIND_TILE_PROVIDER", tile.attr("permission"))
        val complication = application.children("service").single { it.attr("name")!!.endsWith("PendingTasksComplicationService") }
        assertEquals("com.google.android.wearable.permission.BIND_COMPLICATION_MANAGER", complication.attr("permission"))
        val meta = complication.children("meta-data").associate { it.attr("name") to it.attr("value") }
        assertEquals("SHORT_TEXT", meta["android.support.wearable.complications.SUPPORTED_TYPES"])
        assertEquals("0", meta["android.support.wearable.complications.UPDATE_PERIOD_SECONDS"])
    }

    @Test
    fun exactlyOneTile_oneComplication_oneListener_oneActivity() {
        assertEquals(1, application.children("activity").size)
        assertEquals(3, application.children("service").size)
        assertTrue(application.children("receiver").isEmpty())
        assertTrue(application.children("provider").isEmpty())
    }

    // ---- applicationId parity with the phone (a mismatch silently breaks the Data Layer) ----

    private data class AppId(val base: String, val fullSuffix: String?, val dimension: String, val flavors: Set<String>)

    private fun appId(gradle: String): AppId {
        val code = SourceTree.codeOnly(java.io.File(gradle))
        val base = Regex("""defaultConfig\s*\{[\s\S]*?applicationId\s*=\s*"([^"]+)"""").find(code)!!.groupValues[1]
        val fullBlock = Regex("""create\("full"\)\s*\{([\s\S]*?)\}""").find(code)!!.groupValues[1]
        val suffix = Regex("""applicationIdSuffix\s*=\s*"([^"]*)"""").find(fullBlock)?.groupValues?.get(1)
        val dimension = Regex("""flavorDimensions\s*\+=\s*"([^"]+)"""").find(code)!!.groupValues[1]
        val flavors = Regex("""create\("(\w+)"\)\s*\{\s*dimension""").findAll(code).map { it.groupValues[1] }.toSet()
        return AppId(base, suffix, dimension, flavors)
    }

    @Test
    fun applicationIds_matchThePhonePerFlavor_fullIsDevAarsoFull_playIsDevAarso() {
        val phone = appId(SourceTree.repoFile("app/build.gradle.kts").path)
        val watch = appId(SourceTree.file("build.gradle.kts").path)
        assertEquals("dev.aarso", phone.base) // the phone's own contract, asserted so a phone change is noticed here
        assertEquals(phone, watch)
        assertEquals(".full", watch.fullSuffix)
        assertEquals("dist", watch.dimension)
        assertEquals(setOf("full", "play"), watch.flavors)
    }

    @Test
    fun theWatchHasItsOwnFreshVersionSpine_andTheWearOs3Floor() {
        val gradle = build
        assertEquals("1", Regex("""versionCode\s*=\s*(\d+)""").find(gradle)!!.groupValues[1])
        assertEquals("0.1.0", Regex("""versionName\s*=\s*"([^"]+)"""").find(gradle)!!.groupValues[1])
        assertEquals("30", Regex("""minSdk\s*=\s*(\d+)""").find(gradle)!!.groupValues[1])
        assertEquals("36", Regex("""compileSdk\s*=\s*(\d+)""").find(gradle)!!.groupValues[1])
        assertEquals("36", Regex("""targetSdk\s*=\s*(\d+)""").find(gradle)!!.groupValues[1])
        assertEquals("dev.aarso.watch", Regex("""namespace\s*=\s*"([^"]+)"""").find(gradle)!!.groupValues[1])
    }

    // ---- APK budget: no native code of ours, no big assets ----

    @Test
    fun noNativeCode_noJniLibs_noAssets_ofOurOwn() {
        assertFalse(build.contains("externalNativeBuild"))
        assertFalse(build.contains("cmake", ignoreCase = true))
        assertFalse(build.contains("ndkVersion"))
        assertFalse(build.contains("jniLibs"))
        assertFalse(SourceTree.file("src/main/jniLibs").exists())
        assertFalse(SourceTree.file("src/main/cpp").exists())
        assertFalse(SourceTree.file("src/main/assets").exists())
        val soFiles = SourceTree.file("src").walkTopDown().filter { it.isFile && it.extension == "so" }.toList()
        assertTrue("shipped .so: $soFiles", soFiles.isEmpty())
    }

    @Test
    fun resourcesStayTiny() {
        val bytes = SourceTree.file("src/main/res").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("res is $bytes bytes; the wrist APK budget is 200 KB of resources", bytes < 200 * 1024)
    }

    // ---- the icon is the brand mark, copied not invented ----

    @Test
    fun theLauncherIconAndComplicationGlyph_areByteIdenticalCopiesOfTheBrandMonochromeAssets() {
        for (name in listOf("ic_launcher_monochrome.png", "ic_stat_fonebrew.png")) {
            val phone = SourceTree.repoFile("core-engine/src/main/res/drawable-nodpi/$name")
            val watch = SourceTree.file("src/main/res/drawable-nodpi/$name")
            assertTrue("phone asset missing: $phone", phone.isFile)
            assertTrue("watch asset missing: $watch", watch.isFile)
            assertTrue("$name must be a byte-identical copy of the phone's brand asset", phone.readBytes().contentEquals(watch.readBytes()))
        }
        val icon = SourceTree.file("src/main/res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue(icon.contains("@drawable/ic_launcher_monochrome"))
        assertNotNull(application.attr("icon"))
    }
}
