package dev.aarso.domain.model

import dev.aarso.domain.template.TemplateId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultModelPolicyTest {

    private fun spec(id: String, runtime: Runtime) = ModelSpec(
        id = id,
        displayName = id,
        family = "test",
        contextWindow = 4096,
        tokenizerId = "test:$id",
        templateId = TemplateId.PLAIN,
        runtime = runtime,
        watched = runtime == Runtime.CLOUD,
    )

    @Test
    fun persistedOnDeviceModelWins() {
        val specs = listOf(
            spec("local:a.gguf", Runtime.LOCAL_GGUF),
            spec("local:b.gguf", Runtime.LOCAL_GGUF),
        )
        assertEquals("local:b.gguf", DefaultModelPolicy.resolveActive(specs, "local:b.gguf")?.id)
    }

    @Test
    fun unknownPersistedIdFallsBackToFirstLocal() {
        val specs = listOf(
            spec("echo", Runtime.ECHO_DEV),
            spec("local:a.gguf", Runtime.LOCAL_GGUF),
        )
        assertEquals("local:a.gguf", DefaultModelPolicy.resolveActive(specs, "local:gone.gguf")?.id)
    }

    @Test
    fun persistedCloudModelIsNeverRestored() {
        val specs = listOf(
            spec("cloud:p1", Runtime.CLOUD),
            spec("local:a.gguf", Runtime.LOCAL_GGUF),
        )
        assertEquals("local:a.gguf", DefaultModelPolicy.resolveActive(specs, "cloud:p1")?.id)
    }

    @Test
    fun cloudIsNeverChosenEvenWhenItIsTheOnlySpec() {
        val specs = listOf(spec("cloud:p1", Runtime.CLOUD))
        assertNull(DefaultModelPolicy.resolveActive(specs, null))
        assertNull(DefaultModelPolicy.resolveActive(specs, "cloud:p1"))
    }

    @Test
    fun echoIsTheLastResortWhenRegistered() {
        val specs = listOf(spec("cloud:p1", Runtime.CLOUD), spec("echo", Runtime.ECHO_DEV))
        assertEquals("echo", DefaultModelPolicy.resolveActive(specs, null)?.id)
    }

    @Test
    fun emptySpecsResolveToNull() {
        assertNull(DefaultModelPolicy.resolveActive(emptyList(), null))
    }
}
