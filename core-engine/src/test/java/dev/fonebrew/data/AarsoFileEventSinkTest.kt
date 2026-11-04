package dev.fonebrew.data

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AarsoFileEventSinkTest {

    private lateinit var tempDir: File

    @Before fun setUp() {
        tempDir = Files.createTempDirectory("aarso-event-sink-test").toFile()
    }

    @After fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test fun `append creates the file and parent directory on first write`() = runTest {
        val file = File(tempDir, "aarso/events.jsonl")
        val sink = AarsoFileEventSink(file)
        assertTrue(!file.exists())
        sink.append("""{"t":1}""")
        assertTrue(file.exists())
        assertEquals("""{"t":1}""" + "\n", file.readText())
    }

    @Test fun `successive appends accumulate, one JSON object per line`() = runTest {
        val file = File(tempDir, "events.jsonl")
        val sink = AarsoFileEventSink(file)
        sink.append("""{"t":1}""")
        sink.append("""{"t":2}""")
        sink.append("""{"t":3}""")
        assertEquals(listOf("""{"t":1}""", """{"t":2}""", """{"t":3}"""), file.readLines())
    }

    @Test fun `RELATIVE_PATH matches the spec's documented archive path exactly`() {
        assertEquals("aarso/events.jsonl", AarsoFileEventSink.RELATIVE_PATH)
    }
}
