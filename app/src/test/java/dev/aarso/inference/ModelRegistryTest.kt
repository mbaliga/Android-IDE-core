package dev.aarso.inference

import dev.aarso.data.LocalModel
import dev.aarso.domain.cloud.CloudProvider
import dev.aarso.domain.cloud.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Capability flags (W0, provider-generic — CLAUDE.md rule 2) must thread through
 * `toSpec()` correctly: [ModelSpec.supportsVision] from the per-instance
 * [CloudProvider.supportsVision], [ModelSpec.supportsSearch] from the per-kind
 * [ProviderKind.supportsSearch]. Local GGUF specs always report both false — vision
 * (mmproj) and search are out of scope this pass.
 */
class ModelRegistryTest {

    private fun provider(kind: ProviderKind, supportsVision: Boolean) = CloudProvider(
        id = "p1",
        displayName = "Test provider",
        kind = kind,
        baseUrl = kind.defaultBaseUrl,
        model = "some-model",
        contextWindow = 8192,
        supportsVision = supportsVision,
    )

    @Test fun `cloud toSpec threads vision on and search on for a search-capable kind`() {
        val spec = provider(ProviderKind.ANTHROPIC, supportsVision = true).toSpec()

        assertEquals(true, spec.supportsVision)
        assertEquals(true, spec.supportsSearch)
    }

    @Test fun `cloud toSpec threads vision off and search off for a search-incapable kind`() {
        val spec = provider(ProviderKind.OPENAI_COMPATIBLE, supportsVision = false).toSpec()

        assertFalse(spec.supportsVision)
        assertFalse(spec.supportsSearch)
    }

    @Test fun `cloud toSpec keeps vision and search independent`() {
        // Gemini supports search at the kind level even when this particular
        // configured instance has vision turned off — the two flags are unrelated.
        val spec = provider(ProviderKind.GEMINI, supportsVision = false).toSpec()

        assertFalse(spec.supportsVision)
        assertEquals(true, spec.supportsSearch)
    }

    @Test fun `local toSpec is always vision- and search-blind`() {
        val local = LocalModel(File("some-model.gguf"))

        val spec = local.toSpec()

        assertFalse(spec.supportsVision)
        assertFalse(spec.supportsSearch)
    }
}
