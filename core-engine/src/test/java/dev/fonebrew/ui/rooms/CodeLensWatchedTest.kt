package dev.fonebrew.ui.rooms

import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.template.TemplateId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeLensWatchedTest {
    private fun spec(runtime: Runtime, watched: Boolean = false) = ModelSpec(
        id = "m", displayName = "M", family = "test", contextWindow = 4096,
        tokenizerId = "t", templateId = TemplateId.LLAMA3, runtime = runtime, watched = watched,
    )

    @Test fun `a cloud model is watched even if the flag were missed`() {
        assertTrue(codeLensWatched(spec(Runtime.CLOUD)))
        assertTrue(codeLensWatched(spec(Runtime.CLOUD, watched = true)))
    }

    @Test fun `on-device models are not watched`() {
        assertFalse(codeLensWatched(spec(Runtime.LOCAL_GGUF)))
        assertFalse(codeLensWatched(spec(Runtime.AICORE_NANO)))
        assertFalse(codeLensWatched(spec(Runtime.ECHO_DEV)))
    }

    @Test fun `an explicit watched flag is honoured`() {
        assertTrue(codeLensWatched(spec(Runtime.LOCAL_GGUF, watched = true)))
    }

    @Test fun `no runnable model means nothing explains and nothing is watched`() {
        assertFalse(codeLensWatched(null))
    }
}
