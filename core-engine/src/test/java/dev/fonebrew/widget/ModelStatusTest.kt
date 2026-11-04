package dev.fonebrew.widget

import dev.fonebrew.data.GenerationStatus
import dev.fonebrew.domain.cloud.CloudProvider
import dev.fonebrew.domain.cloud.ProviderKind
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.template.TemplateId
import dev.fonebrew.inference.toSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStatusTest {

    private fun spec(id: String, runtime: Runtime, name: String = id) = ModelSpec(
        id = id, displayName = name, family = "f", contextWindow = 4096, tokenizerId = "t",
        templateId = TemplateId.PLAIN, runtime = runtime, watched = runtime == Runtime.CLOUD,
    )

    private val gguf = spec("gguf", Runtime.LOCAL_GGUF, "Llama 3.2 3B")
    private val nano = spec("nano", Runtime.AICORE_NANO)
    private val cloud = spec("cloud", Runtime.CLOUD, "Work provider · some-model")
    private val echo = spec("echo", Runtime.ECHO_DEV)

    @Test
    fun beforeAnyChatReports_theDefaultPolicyDecides_andNeverPicksCloud() {
        val notReported = GenerationStatus.Snapshot()
        // A persisted cloud choice is not resumed at start (DefaultModelPolicy): on-device wins.
        val active = ModelStatus.activeModel(listOf(cloud, gguf), notReported, persistedId = "cloud")
        assertEquals(ActiveModel("Llama 3.2 3B", onDevice = true), active)
        assertNull(ModelStatus.activeModel(listOf(cloud), notReported, persistedId = "cloud"))
    }

    @Test
    fun onceTheChatReports_itsOwnChoiceWins_includingACloudModel() {
        val reported = GenerationStatus.Snapshot(reported = true, activeModelId = "cloud")
        assertEquals(ActiveModel("Work provider · some-model", onDevice = false), ModelStatus.activeModel(listOf(gguf, cloud), reported, null))
        val none = GenerationStatus.Snapshot(reported = true, activeModelId = null)
        assertNull("the chat has no model: so does the widget", ModelStatus.activeModel(listOf(gguf), none, "gguf"))
        val gone = GenerationStatus.Snapshot(reported = true, activeModelId = "deleted")
        assertNull(ModelStatus.activeModel(listOf(gguf), gone, null))
    }

    @Test
    fun modelNames_areOneLine() {
        val odd = spec("x", Runtime.LOCAL_GGUF, "Name\nwith\tbreaks")
        assertEquals("Name with breaks", ModelStatus.activeModel(listOf(odd), GenerationStatus.Snapshot(), null)!!.name)
    }

    @Test
    fun onDeviceCount_isDownloadsAndNano_notTheDevEchoNorCloud() {
        assertEquals(2, ModelStatus.modelsOnDevice(listOf(gguf, nano, cloud, echo)))
        assertEquals(0, ModelStatus.modelsOnDevice(emptyList()))
    }

    @Test
    fun phase_followsTheChat_andIdleSplitsIntoReadyOrNoModel() {
        val active = ActiveModel("m", true)
        fun phaseOf(p: GenerationStatus.Phase, a: ActiveModel?) =
            ModelStatus.build(GenerationStatus.Snapshot(phase = p), a, 0, 0).phase
        assertEquals(ModelPhase.LOADING, phaseOf(GenerationStatus.Phase.LOADING, active))
        assertEquals(ModelPhase.GENERATING, phaseOf(GenerationStatus.Phase.GENERATING, active))
        assertEquals(ModelPhase.READY, phaseOf(GenerationStatus.Phase.IDLE, active))
        assertEquals(ModelPhase.NO_MODEL, phaseOf(GenerationStatus.Phase.IDLE, null))
    }

    @Test
    fun build_carriesModeTimesAndCounts() {
        val s = GenerationStatus.Snapshot(
            phase = GenerationStatus.Phase.GENERATING, mode = GenerationStatus.Mode.COUNCIL,
            reported = true, since = 10L, lastEndedAt = 5L,
        )
        val m = ModelStatus.build(s, ActiveModel("m", false), modelsOnDevice = 3, downloadsRunning = 1)
        assertEquals(GenerationStatus.Mode.COUNCIL, m.mode)
        assertEquals(10L, m.since)
        assertEquals(5L, m.lastEndedAt)
        assertEquals(3, m.modelsOnDevice)
        assertEquals(1, m.downloadsRunning)
        assertFalse(m.model!!.onDevice)
    }

    @Test
    fun theCouncilIsNeverCalledAMixtureOfExperts() {
        // Binding rule 3: the mode is a Council. Guard the enum the widget's words are keyed on.
        assertTrue(GenerationStatus.Mode.entries.none { it.name.contains("MOE") || it.name.contains("EXPERT") })
        assertTrue(GenerationStatus.Mode.entries.any { it.name == "COUNCIL" })
    }

    @Test
    fun aRealCloudSpec_isNamedWithoutItsOwnGlyph_soTheWidgetDrawsTheMarkOnce() {
        // Built the way the app builds it: CloudProvider.toSpec() prefixes "☁ " to the display name.
        val real = CloudProvider(
            id = "w", displayName = "Work", kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = ProviderKind.OPENAI_COMPATIBLE.defaultBaseUrl, model = "gpt-4o", contextWindow = 128_000,
        ).toSpec()
        assertTrue("precondition: the spec name carries the glyph", real.displayName.startsWith("☁"))
        val active = ModelStatus.activeModel(listOf(real), GenerationStatus.Snapshot(reported = true, activeModelId = real.id), null)!!
        assertEquals(ActiveModel("Work · gpt-4o", onDevice = false), active)
        assertFalse(active.name.contains("☁"))
    }

    @Test
    fun aTurnThatDoesntRunOnTheChatModel_carriesItsOwnProvenance_andCloudWins() {
        val onDeviceChat = ActiveModel("Llama 3.2 3B", onDevice = true)
        // An image from a cloud provider while the chat's text model is on-device.
        val cloudImage = GenerationStatus.Snapshot(
            phase = GenerationStatus.Phase.GENERATING, mode = GenerationStatus.Mode.IMAGE, reported = true, runOnDevice = false,
        )
        val m = ModelStatus.build(cloudImage, onDeviceChat, 1, 0)
        assertEquals(false, m.runOnDevice)
        assertTrue("cloud work is never shown as on-device", ModelStatus.usesCloud(m))

        // A turn on the chat's own model: the model decides.
        val text = cloudImage.copy(mode = GenerationStatus.Mode.TEXT, runOnDevice = null)
        assertFalse(ModelStatus.usesCloud(ModelStatus.build(text, onDeviceChat, 1, 0)))
        assertTrue(ModelStatus.usesCloud(ModelStatus.build(text, ActiveModel("Work · gpt-4o", onDevice = false), 1, 0)))

        // Idle: no turn, so no turn provenance, whatever the snapshot says.
        val idle = cloudImage.copy(phase = GenerationStatus.Phase.IDLE)
        assertNull(ModelStatus.build(idle, onDeviceChat, 1, 0).runOnDevice)
        assertFalse(ModelStatus.usesCloud(ModelStatus.build(idle, onDeviceChat, 1, 0)))
    }
}
