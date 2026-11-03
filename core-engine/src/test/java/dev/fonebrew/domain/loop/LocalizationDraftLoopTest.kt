package dev.fonebrew.domain.loop

import dev.fonebrew.domain.council.Generator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Studio P8's OSS content-localization dependency: `core-engine/src/main/assets/loops/
 * localization-draft.floop.json`, a `locale`/`fieldKey`/`sourceText`-params loop template
 * shipped in core as an open, forkable starting point (`LoopPackageCodec.PACKAGE_FILE_EXTENSION`,
 * always UNSIGNED — see that codec's own KDoc). This is the "trust but verify the committed
 * output" relationship [dev.fonebrew.ui.graph.GraphRoomAssetTest] has to `graph-room.html`: reads
 * the file exactly as it was committed (not the fixture used to generate it — that generator ran
 * once by hand and is gone, same reasoning as that class's own KDoc), decodes it through the
 * real, shipped [LoopPackageCodec] (the same safety scan any user-imported `.floop.json` gets —
 * see [LoopTemplateAssets]'s KDoc for why bundling isn't a trust shortcut), and actually **runs**
 * the decoded graph via [GraphRunner] against a small budget and a fake/Echo-style [Generator] —
 * no device or real model needed, same posture as [GraphRunnerTest].
 */
class LocalizationDraftLoopTest {

    private val packageBytes: ByteArray by lazy { readAsset() }

    /** Same working-directory-hunting fallback as [dev.fonebrew.ui.graph.GraphRoomAssetTest] —
     *  Gradle's default unit-test working directory is the module's own project dir
     *  (`core-engine/`), which resolves the plain relative path directly. */
    private fun readAsset(): ByteArray {
        val relative = "src/main/assets/loops/localization-draft.floop.json"
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readBytes()
            val direct = File(dir, "core-engine/$relative")
            if (direct.isFile) return direct.readBytes()
            dir = dir.parentFile ?: return@repeat
        }
        fail(
            "couldn't find core-engine/src/main/assets/loops/localization-draft.floop.json from " +
                "working directory '${System.getProperty("user.dir")}'.",
        )
        error("unreachable")
    }

    // ── Parses / validates ──────────────────────────────────────────────────────────────────

    @Test
    fun `the package decodes clean through the real codec`() {
        val decoded = LoopPackageCodec.decode(packageBytes)
        assertEquals("fonebrew.oss.localization-draft", decoded.manifest.packageIdentity.loopId)
        assertEquals("1.0.0", decoded.manifest.packageIdentity.semanticVersion)
        assertEquals("Apache-2.0", decoded.manifest.license)
        // UNSIGNED, honestly — no publisher-key infrastructure exists in this codebase.
        assertEquals(null, decoded.manifest.signatureRef)
        assertEquals(3, decoded.graph.nodes.size)
        assertEquals(2, decoded.graph.edges.size)
    }

    @Test
    fun `the shipped package scans clean, same check a user-imported package gets`() {
        val findings = LoopPackageCodec.scan(packageBytes)
        assertTrue("expected no findings, got $findings", findings.isEmpty())
    }

    @Test
    fun `the graph's only params are locale, fieldKey and sourceText`() {
        val decoded = LoopPackageCodec.decode(packageBytes)
        val params = LoopParams.scan(decoded.graph, decoded.objective)
        assertEquals(setOf("fieldKey", "locale", "sourceText"), params.toSet())
    }

    @Test
    fun `all three params are reported missing when none are supplied`() {
        val decoded = LoopPackageCodec.decode(packageBytes)
        val missing = LoopParams.missing(decoded.graph, decoded.objective, emptyMap())
        assertEquals(setOf("fieldKey", "locale", "sourceText"), missing.toSet())
    }

    @Test
    fun `nothing is missing once all three params are supplied`() {
        val decoded = LoopPackageCodec.decode(packageBytes)
        val missing = LoopParams.missing(decoded.graph, decoded.objective, sampleParams())
        assertTrue("expected nothing missing, got $missing", missing.isEmpty())
    }

    // ── Runs, under a small budget, against a fake/Echo-style generator ────────────────────

    private fun sampleParams() = mapOf(
        "locale" to "es-MX",
        "fieldKey" to "onboarding.welcome_title",
        "sourceText" to "Welcome back!",
    )

    /** Echo-style: no real model, just enough behavior (echoes the substituted system prompt
     *  back, prefixed) to prove params actually reached the node — same spirit as
     *  [dev.fonebrew.inference.EchoInferenceEngine], adapted to the [Generator] seam
     *  [GraphRunner] actually runs against (mirrors [GraphRunnerTest]'s own fakes). */
    private fun fakeGenerator(): (dev.fonebrew.domain.bpmn.BpmnNode) -> Generator = { _ ->
        Generator { system, _ -> "draft: $system" }
    }

    @Test
    fun `refuses to start when a param is missing, names it, runs nothing`() = runTest {
        val decoded = LoopPackageCodec.decode(packageBytes)
        val result = GraphRunner(fakeGenerator()).run(
            decoded.graph, decoded.objective,
            params = mapOf("locale" to "es-MX"), // fieldKey, sourceText missing
        )
        assertFalse(result.reachedEnd)
        assertTrue(result.stoppedBecause.startsWith("missing params"))
        assertTrue(result.stoppedBecause.contains("fieldKey"))
        assertTrue(result.stoppedBecause.contains("sourceText"))
        assertTrue(result.steps.isEmpty())
    }

    @Test
    fun `runs to completion under a small budget, substituting all three params`() = runTest {
        val decoded = LoopPackageCodec.decode(packageBytes)
        val result = GraphRunner(fakeGenerator()).run(
            decoded.graph, decoded.objective,
            params = sampleParams(),
            budget = LoopBudget(maxSteps = 3, maxWallMs = 5_000L),
        )
        assertTrue("expected the run to reach its end event, stopped because: ${result.stoppedBecause}", result.reachedEnd)
        assertEquals("reached end", result.stoppedBecause)
        assertEquals(1, result.steps.size)
        val step = result.steps.single()
        assertEquals("translate", step.nodeId)
        assertEquals("translator", step.role)
        // The node's systemPrompt had its ${locale}/${fieldKey}/${sourceText} placeholders
        // substituted before the generator ever saw it — proves params flowed through, not
        // just that the run didn't crash.
        assertTrue(step.output.contains("es-MX"))
        assertTrue(step.output.contains("onboarding.welcome_title"))
        assertTrue(step.output.contains("Welcome back!"))
        assertFalse("no unsubstituted placeholder should remain", step.output.contains("\${"))
    }

    @Test
    fun `an exhausted step budget stops the run before the translate node executes`() = runTest {
        val decoded = LoopPackageCodec.decode(packageBytes)
        val result = GraphRunner(fakeGenerator()).run(
            decoded.graph, decoded.objective,
            params = sampleParams(),
            budget = LoopBudget(maxSteps = 0),
        )
        assertFalse(result.reachedEnd)
        assertEquals("budget:steps", result.stoppedBecause)
        assertTrue(result.steps.isEmpty())
    }
}
