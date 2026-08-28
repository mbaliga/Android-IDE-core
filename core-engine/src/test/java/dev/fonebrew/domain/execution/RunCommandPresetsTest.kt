package dev.fonebrew.domain.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunCommandPresetsTest {

    @Test
    fun `detect -- a gradle repo suggests gradlew test`() {
        assertEquals("./gradlew test", RunCommandPresets.detect(listOf("gradlew", "settings.gradle.kts", "app")))
    }

    @Test
    fun `detect -- a node repo suggests npm test`() {
        assertEquals("npm test", RunCommandPresets.detect(listOf("package.json", "src")))
    }

    @Test
    fun `detect -- nothing recognizable returns null, never a wrong guess`() {
        assertNull(RunCommandPresets.detect(listOf("README.md", "LICENSE")))
    }

    @Test
    fun `detect -- first match wins when several manifests are present`() {
        assertEquals("./gradlew test", RunCommandPresets.detect(listOf("gradlew", "package.json")))
    }
}
