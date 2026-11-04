package dev.fonebrew.data.watch

import dev.fonebrew.watchbridge.WatchPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guards for the relay (the properties that must stay true as the code is edited, and that
 * a behavioural test cannot see): where Android and Play Services are allowed to appear, that the
 * service holds no logic, that nothing about it is constructed at app start, that no key-handling API
 * is reachable from the relay's sources, and that the manifest's filter equals the shared contract.
 *
 * These read source/manifest text on purpose; they pin the architecture, not behaviour. They run from the
 * `core-engine` module directory (Gradle's working directory) and tolerate the repository root.
 */
class WatchRelayStructureTest {

    private fun moduleDir(): File {
        val dir = listOf(File("."), File("core-engine")).firstOrNull { File(it, "src/main/java/dev/fonebrew/data/watch").isDirectory }
        assertNotNull("cannot find core-engine from ${File(".").absolutePath}", dir)
        return dir!!
    }

    private fun repoRoot(): File = moduleDir().canonicalFile.parentFile!!

    private fun relaySources(): List<File> =
        File(moduleDir(), "src/main/java/dev/fonebrew/data/watch").listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }

    /** Code lines only: KDoc and comments may NAME things to explain a rule. */
    private fun codeLines(f: File): List<String> =
        f.readLines().map { it.trim() }.filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }

    private val gmsFiles = setOf("GmsWearTransport.kt", "WearRelayService.kt")

    @Test
    fun scansARealSetOfSources() {
        val names = relaySources().map { it.name }.toSet()
        assertTrue(names.toString(), names.containsAll(setOf("WearRelayPresenter.kt", "WristTurnRunner.kt", "WearRelayService.kt", "GmsWearTransport.kt", "ReplyChunker.kt")))
    }

    @Test
    fun androidAndPlayServices_appearOnlyInTheTwoDeviceOnlyFiles() {
        val forbidden = Regex("""^import (android\.|androidx\.|com\.google\.android\.)""")
        for (f in relaySources().filterNot { it.name in gmsFiles }) {
            for (line in codeLines(f)) {
                assertFalse("${f.name}: pure relay logic must stay Android-free, found: $line", forbidden.containsMatchIn(line))
            }
        }
        // ...and the two that do use them are exactly where the design says GMS code lives.
        for (name in gmsFiles) {
            assertTrue("$name should be the GMS-facing file", relaySources().first { it.name == name }.readText().contains("com.google.android.gms"))
        }
    }

    @Test
    fun theService_holdsNoLogic_itOnlyResolvesTheRelayAndHandsOverTheMessage() {
        val service = relaySources().first { it.name == "WearRelayService.kt" }
        val code = codeLines(service).joinToString("\n")
        for (logic in listOf("WatchBridgeCodec", "StreamFraming", "TaskStore", "InferenceEngine", "MessageTreeRepository", "AskRequest", "TaskToggle", "LedgerStore", "ReplyChunker", "WristSnapshots")) {
            assertFalse("the service must not touch $logic; that belongs in the presenter", code.contains(logic))
        }
        assertTrue("it must delegate to the relay", code.contains("relay.onMessage("))
        assertTrue("it must resolve through the container's lazy seam", code.contains("container?.wearRelay") || code.contains("container.wearRelay"))
        assertTrue("it must stay small: ${codeLines(service).count { it.isNotBlank() }} code lines", codeLines(service).count { it.isNotBlank() } < 45)
    }

    @Test
    fun noKeyHandlingApi_isReachableFromTheRelay() {
        val keyApis = listOf("apiKey", "ProviderStore", "KeystoreSecret", "CloudProvider", "CloudEngineFactory", "providers.")
        for (f in relaySources()) {
            val code = codeLines(f).joinToString("\n")
            for (api in keyApis) assertFalse("${f.name} references $api: keys live only in the phone Keystore", code.contains(api))
        }
    }

    @Test
    fun theRelayNeverLogsUserText_noLogCallsOutsideTheServicesClassNameOnlyWarning() {
        val logCalls = Regex("""\b(Log\.[a-z]|println|Timber|logger)""")
        for (f in relaySources()) {
            val hits = codeLines(f).filter { logCalls.containsMatchIn(it) }
            if (f.name == "WearRelayService.kt") {
                assertTrue("only the class-name warning is allowed: $hits", hits.all { it.contains("javaClass.simpleName") })
            } else {
                assertTrue("${f.name} logs: $hits", hits.isEmpty())
            }
        }
    }

    @Test
    fun theContainerSeam_isLazy_andNothingElseInTheContainerTouchesTheRelay() {
        val text = File(moduleDir(), "src/main/java/dev/fonebrew/di/AppContainer.kt").readText()
        val seam = Regex("""val wearRelay: dev\.fonebrew\.data\.watch\.WearRelay by lazy \{""").find(text)
        assertNotNull("wearRelay must be a `by lazy` property", seam)

        // Find the lazy block's extent by brace matching, then require every code reference to the relay package to be inside it.
        var depth = 0
        var end = -1
        for (i in seam!!.range.last until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) { end = i; break }
            }
        }
        assertTrue("lazy block did not close", end > 0)
        val outside = text.substring(0, seam.range.first) + text.substring(end + 1)
        val offending = outside.lines().map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
            .filter { it.contains("data.watch.") || it.contains("wearRelay") }
        assertTrue("the relay is constructed outside its lazy seam: $offending", offending.isEmpty())
    }

    @Test
    fun theManifestFilter_equalsTheSharedContract_andNothingWider() {
        val manifest = File(moduleDir(), "src/main/AndroidManifest.xml").readText()
        val service = Regex("""<service\s+android:name="dev\.fonebrew\.data\.watch\.WearRelayService"(.*?)</service>""", RegexOption.DOT_MATCHES_ALL).find(manifest)
        assertNotNull("WearRelayService must be declared in the library manifest", service)
        val body = service!!.groupValues[1]

        assertTrue("Play services binds it from outside the process", body.contains("""android:exported="true""""))
        assertEquals("exactly one intent filter", 1, Regex("<intent-filter>").findAll(body).count())
        assertEquals(
            "exactly one action: a message arrived",
            listOf("com.google.android.gms.wearable.MESSAGE_RECEIVED"),
            Regex("""<action android:name="([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList(),
        )
        assertTrue(body.contains("""android:scheme="wear""""))
        assertTrue(body.contains("""android:host="*""""))
        val prefix = Regex("""android:pathPrefix="([^"]+)"""").find(body)!!.groupValues[1]
        assertEquals("the filter prefix must be the contract's, not a copy that can drift", WatchPaths.PREFIX, prefix)
        assertFalse("the phone only opens channels; it never receives one", body.contains("CHANNEL_EVENT"))
        assertFalse("no match-all listener", body.contains("BIND_LISTENER"))

        // Every path the watch may send to the phone is under the prefix the phone subscribed to.
        for (p in listOf(WatchPaths.SYNC, WatchPaths.ASK, WatchPaths.TASK_TOGGLE)) assertTrue(p, p.startsWith(prefix))
    }

    @Test
    fun theServiceClass_exists_andIsAWearableListener() {
        val cls = Class.forName("dev.fonebrew.data.watch.WearRelayService", false, javaClass.classLoader)
        var c: Class<*>? = cls
        var found = false
        while (c != null) { if (c.name == "com.google.android.gms.wearable.WearableListenerService") found = true; c = c.superclass }
        assertTrue(found)
    }

    @Test
    fun theDataLayerPin_isOneExactStableVersion_inTheCatalog() {
        val toml = File(repoRoot(), "gradle/libs.versions.toml").readText()
        val version = Regex("""^playServicesWearable = "([^"]+)"""", RegexOption.MULTILINE).find(toml)?.groupValues?.get(1)
        assertNotNull("playServicesWearable must be pinned in libs.versions.toml", version)
        assertTrue("must be an exact x.y.z release, never a range or a suffix: $version", Regex("""\d+\.\d+\.\d+""").matches(version!!))
        assertTrue(toml.contains("""play-services-wearable = { group = "com.google.android.gms", name = "play-services-wearable", version.ref = "playServicesWearable" }"""))
    }

    @Test
    fun theLicenseAllowlist_hasNoDataLayerSpecificEntry_theScopedGmsRuleAlreadyAdmitsIt() {
        val allow = File(repoRoot(), "config/allowed-licenses.json").readText()
        assertTrue("the existing GMS-scoped rule is what admits the artifact", allow.contains("com\\\\.google\\\\.(android\\\\.gms"))
        assertFalse("no per-artifact allow entry was added for the Data Layer", allow.contains("play-services-wearable"))
    }
}
