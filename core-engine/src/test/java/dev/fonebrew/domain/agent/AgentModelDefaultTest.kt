package dev.fonebrew.domain.agent

import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.template.TemplateId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelDefaultTest {
    private fun spec(id: String, runtime: Runtime) = ModelSpec(
        id = id, displayName = id, family = "t", contextWindow = 4096, tokenizerId = "t",
        templateId = TemplateId.LLAMA3, runtime = runtime,
        providerId = if (runtime == Runtime.CLOUD) "p" else null, watched = runtime == Runtime.CLOUD,
    )

    @Test fun `a cloud model listed first is never the default when an on-device model exists`() {
        val list = listOf(spec("cloud-a", Runtime.CLOUD), spec("local-b", Runtime.LOCAL_GGUF))
        assertEquals("local-b", AgentModelDefault.defaultModelId(list))
        assertFalse(AgentModelDefault.needsExplicitCloudChoice(list))
    }

    @Test fun `with only cloud models there is no default and the user must choose explicitly`() {
        val list = listOf(spec("cloud-a", Runtime.CLOUD), spec("cloud-b", Runtime.CLOUD))
        assertNull(AgentModelDefault.defaultModelId(list))
        assertTrue(AgentModelDefault.needsExplicitCloudChoice(list))
    }

    @Test fun `an empty list has no default and is not a cloud choice`() {
        assertNull(AgentModelDefault.defaultModelId(emptyList()))
        assertFalse(AgentModelDefault.needsExplicitCloudChoice(emptyList()))
    }

    @Test fun `on-device nano and echo runtimes count as on-device`() {
        assertEquals("n", AgentModelDefault.defaultModelId(listOf(spec("c", Runtime.CLOUD), spec("n", Runtime.AICORE_NANO))))
        assertEquals("e", AgentModelDefault.defaultModelId(listOf(spec("e", Runtime.ECHO_DEV))))
    }
}
