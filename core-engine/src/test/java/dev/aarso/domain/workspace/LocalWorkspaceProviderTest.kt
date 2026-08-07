package dev.aarso.domain.workspace

import dev.aarso.contracts.workspace.ResourceProvider
import dev.aarso.contracts.workspace.ResourceUri
import dev.aarso.contracts.workspace.ResourceWrite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Real filesystem I/O against a JVM temp directory -- no fake/mock, since [LocalWorkspaceProvider]
 * is itself just a thin wrapper over [java.io.File]/[java.nio.file.WatchService] with no Android
 * dependency, unlike the Room-backed stores elsewhere in this module.
 */
class LocalWorkspaceProviderTest {

    private fun newProvider(): Pair<LocalWorkspaceProvider, java.io.File> {
        val root = Files.createTempDirectory("workspace-provider-test").toFile()
        return LocalWorkspaceProvider(root) to root
    }

    private fun uri(path: String) = ResourceUri(ResourceProvider.LOCAL, "local://$path")

    @Test
    fun `stat on a missing file reports exists=false`() = runTest {
        val (provider, _) = newProvider()
        val stat = provider.stat(uri("nope.txt"))
        assertFalse(stat.exists)
    }

    @Test
    fun `write then stat then read round-trips content and a stable content-addressed revision`() = runTest {
        val (provider, _) = newProvider()
        val write = provider.write(uri("a.txt"), "hello".toByteArray(StandardCharsets.UTF_8), expectedRevision = null)
        assertTrue(write is ResourceWrite.Success)
        val newRevision = (write as ResourceWrite.Success).newRevision

        val stat = provider.stat(uri("a.txt"))
        assertTrue(stat.exists)
        assertEquals(newRevision, stat.revision)

        val read = provider.read(uri("a.txt"))
        assertEquals("hello", String(read.bytes, StandardCharsets.UTF_8))
        assertEquals(newRevision, read.revision)
    }

    @Test
    fun `write with a stale expectedRevision returns Conflict, not a silent overwrite`() = runTest {
        val (provider, _) = newProvider()
        val first = provider.write(uri("b.txt"), "v1".toByteArray(StandardCharsets.UTF_8), expectedRevision = null)
        val firstRevision = (first as ResourceWrite.Success).newRevision

        // Simulate a second writer changing the file out from under us.
        provider.write(uri("b.txt"), "v2-from-elsewhere".toByteArray(StandardCharsets.UTF_8), expectedRevision = firstRevision)

        val staleWrite = provider.write(uri("b.txt"), "v3-conflicting".toByteArray(StandardCharsets.UTF_8), expectedRevision = firstRevision)
        assertTrue(staleWrite is ResourceWrite.Conflict)
        val conflict = staleWrite as ResourceWrite.Conflict
        assertEquals(firstRevision, conflict.expectedRevision)
        assertFalse(conflict.actualRevision == firstRevision)

        // The on-disk content is still "v2-from-elsewhere" -- the conflicting write never landed.
        val read = provider.read(uri("b.txt"))
        assertEquals("v2-from-elsewhere", String(read.bytes, StandardCharsets.UTF_8))
    }

    @Test
    fun `write with a matching expectedRevision succeeds and advances the revision`() = runTest {
        val (provider, _) = newProvider()
        val first = provider.write(uri("c.txt"), "v1".toByteArray(StandardCharsets.UTF_8), expectedRevision = null)
        val firstRevision = (first as ResourceWrite.Success).newRevision

        val second = provider.write(uri("c.txt"), "v2".toByteArray(StandardCharsets.UTF_8), expectedRevision = firstRevision)
        assertTrue(second is ResourceWrite.Success)
        assertFalse((second as ResourceWrite.Success).newRevision == firstRevision)
    }

    @Test
    fun `list enumerates a directory's entries sorted by name`() = runTest {
        val (provider, root) = newProvider()
        provider.write(uri("z.txt"), "z".toByteArray(), expectedRevision = null)
        provider.write(uri("a.txt"), "a".toByteArray(), expectedRevision = null)
        provider.write(uri("m.txt"), "m".toByteArray(), expectedRevision = null)

        val page = provider.list(uri("."))
        val names = page.items.map { it.uri.displayPath }
        assertEquals(listOf("a.txt", "m.txt", "z.txt"), names)
        assertEquals(null, page.nextCursor)
    }

    @Test
    fun `watch observes a write inside the watched directory`() = kotlinx.coroutines.runBlocking {
        // Deliberately runBlocking, not runTest: the watcher runs on a real background Thread
        // (java.nio.file.WatchService), so this needs real wall-clock time, not runTest's virtual
        // time -- see LocalWorkspaceProvider.watch()'s own doc comment on owner-verified timing.
        val (provider, _) = newProvider()
        val events = provider.watch(uri("."))
        val collected = kotlinx.coroutines.withTimeout(5_000) {
            val deferred = kotlinx.coroutines.async { events.first() }
            // Give the WatchService thread a moment to register before the write happens.
            kotlinx.coroutines.delay(300)
            provider.write(uri("watched.txt"), "hi".toByteArray(), expectedRevision = null)
            deferred.await()
        }
        assertEquals("watched.txt", collected.uri.displayPath)
    }
}
