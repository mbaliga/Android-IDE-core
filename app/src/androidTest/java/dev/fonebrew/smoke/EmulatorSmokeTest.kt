package dev.fonebrew.smoke

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aarso.interactionmode.InteractionMode
import dev.fonebrew.FonebrewApp
import dev.fonebrew.di.AppContainer
import dev.fonebrew.ui.MainActivity
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator smoke (docs/ci/emulator-smoke.md): proves the app LAUNCHES and RENDERS, which the JVM
 * gate cannot (a launch/render crash passes it - CLAUDE.md "CI caveat"). It does NOT prove
 * on-device behaviour: an x86_64 emulator is not the owner's phone, no native engine runs, and no
 * model is loaded.
 *
 * Hermetic by construction: no network, no model file, no API key. The Echo dev engine (debug
 * builds only) is selected through the same public store setters the UI itself calls - no
 * test-only seam in production code. Nothing here swallows an exception: every wait is a
 * [androidx.compose.ui.test.junit4.ComposeTestRule.waitUntil] that throws on timeout.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorSmokeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    private val container: AppContainer
        get() = (ApplicationProvider.getApplicationContext<Context>() as FonebrewApp).container

    @After
    fun closeActivity() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** Past onboarding, Regular shell, Echo as the active model - all via existing public setters. */
    private fun seedRegularWithEcho() {
        container.sessionStore.setOnboardingDone()
        container.interactionMode.setMode(InteractionMode.REGULAR)
        container.sessionStore.setActiveModelId(ECHO_MODEL_ID)
    }

    private fun waitFor(what: String, timeoutMs: Long = LONG_WAIT_MS, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (t: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $what", t)
        }
    }

    private fun tab(label: String) = hasContentDescription(label) and hasClickAction()

    private fun isPresent(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** The libraries this app can JNI-load; a Fonebrew native lib in /proc/self/maps means one loaded. */
    private fun loadedFonebrewNativeLibs(): List<String> =
        File("/proc/self/maps").readLines()
            .map { it.substringAfterLast(' ') }
            .filter { it.substringAfterLast('/').startsWith("libaarso_") }
            .distinct()

    private fun assertNoNativeEngineLoaded(where: String) {
        assertEquals(
            "$where: a native engine library was loaded; JNI loads must stay lazy " +
                "(EngineProvider/SdImageEngine/NativePty) so Echo-only launch needs no .so",
            emptyList<String>(),
            loadedFonebrewNativeLibs(),
        )
    }

    // (a) Cold launch reaches the first screen: not a crash, not the crash-recovery screen.
    @Test
    fun coldLaunchReachesFirstScreen() {
        launch()
        // MainActivity returns early and finishes itself when CrashRecovery has a pending report,
        // so "still RESUMED as MainActivity" means the recovery screen was not taken.
        waitFor("MainActivity RESUMED") { scenario!!.state == androidx.lifecycle.Lifecycle.State.RESUMED }
        scenario!!.onActivity { assertFalse("MainActivity finished itself (crash recovery?)", it.isFinishing) }

        val onboardingFirstPage = hasText("AI models run on this phone", substring = true)
        val shellChatTab = tab("Chat")
        // A fresh install lands on onboarding page 1; a previously-seeded process lands on the
        // shell. Either is a legitimate first screen - anything else (blank, recovery) is not.
        waitFor("onboarding page 1 or the Chat tab") { isPresent(onboardingFirstPage) || isPresent(shellChatTab) }
        assertNoNativeEngineLoaded("after cold launch")
    }

    // (b) Echo engine, one message, an assistant turn renders (the markdown path that once
    // crashed on every send). The reply is "echo: <user text>", so the user text carries markup.
    @Test
    fun echoSendRendersAssistantTurn() {
        seedRegularWithEcho()
        launch()
        waitFor("composer") { isPresent(hasSetTextAction()) }

        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput(SEND_TEXT)
        waitFor("Send button") { isPresent(hasText("Send") and hasClickAction()) }
        compose.onAllNodes(hasText("Send") and hasClickAction()).onLast().performClick()

        // Only the assistant bubble contains "echo: " - the user bubble holds the raw input.
        waitFor("assistant turn 'echo: smokeping'") { isPresent(hasText("echo: smokeping", substring = true)) }
        assertNoNativeEngineLoaded("after an Echo send")
    }

    // (c) Every top-level destination of the Regular shell composes once.
    @Test
    fun regularShellDestinationsEachCompose() {
        seedRegularWithEcho()
        launch()
        waitFor("Regular shell tab bar") { isPresent(tab("Settings")) }

        // bottom-tab label -> a marker that exists only inside that destination.
        val destinations = listOf(
            "Chats" to hasContentDescription("Starred"),
            "Projects" to hasContentDescription("To-do"),
            "Develop" to hasContentDescription("Hardware"),
            "Settings" to hasContentDescription("General"),
            "Chat" to hasSetTextAction(),
        )
        for ((label, marker) in destinations) {
            // The bottom bar is the last node with this label (a room's own tab row can reuse it).
            compose.onAllNodesWithContentDescription(label).onLast().performClick()
            waitFor("destination '$label' to compose (marker $marker)") { isPresent(marker) }
        }
    }

    private companion object {
        const val ECHO_MODEL_ID = "echo-chatml"
        const val SEND_TEXT = "smokeping **render** check"
        const val LONG_WAIT_MS = 30_000L
    }
}
