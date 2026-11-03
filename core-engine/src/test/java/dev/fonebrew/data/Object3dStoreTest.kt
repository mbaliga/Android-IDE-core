package dev.fonebrew.data

import dev.fonebrew.domain.object3d.Object3dCloudProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Object3dStore]'s Android/Context-touching parts (file I/O under `filesDir`) are exercised
 *  on-device only (CLAUDE.md "Environment honesty" — no device in this container, same convention
 *  as [ImageStore] and every other `Context`-backed store in `dev.fonebrew.data`); the pieces
 *  below are the pure, Context-free halves — content-hashed naming and the node-metadata
 *  builder — and those ARE JVM-tested here. */
class Object3dStoreTest {

    @Test fun `file names are content-hashed and stable`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val name1 = Object3dStore.fileNameFor(bytes, "glb")
        val name2 = Object3dStore.fileNameFor(bytes.copyOf(), "glb")
        assertEquals(name1, name2)
        assertTrue(name1.endsWith(".glb"))
        // sha256 hex is 64 chars + ".glb"
        assertEquals(64 + 4, name1.length)
    }

    @Test fun `different bytes hash to different names`() {
        val name1 = Object3dStore.fileNameFor(byteArrayOf(1), "obj")
        val name2 = Object3dStore.fileNameFor(byteArrayOf(2), "obj")
        assertFalse(name1 == name2)
    }

    @Test fun `isObject3dNode is true only when the file key is present`() {
        assertTrue(Object3dNodeMeta.isObject3dNode(mapOf(Object3dNodeMeta.KEY_FILE to "x.glb")))
        assertFalse(Object3dNodeMeta.isObject3dNode(mapOf("image" to "x.png")))
        assertFalse(Object3dNodeMeta.isObject3dNode(emptyMap()))
    }

    @Test fun `metadata for an on-device DSL generation carries prompt but no provider`() {
        val meta = Object3dNodeMeta.metadata(
            relativePath = "abcd.json",
            format = Object3dNodeMeta.DSL_JSON_FORMAT,
            source = Object3dSource.ON_DEVICE_GENERATED,
            provider = null,
            prompt = "a red box",
        )
        assertEquals("abcd.json", meta[Object3dNodeMeta.KEY_FILE])
        assertEquals(Object3dNodeMeta.DSL_JSON_FORMAT, meta[Object3dNodeMeta.KEY_FORMAT])
        assertEquals("ON_DEVICE_GENERATED", meta[Object3dNodeMeta.KEY_SOURCE])
        assertEquals("a red box", meta[Object3dNodeMeta.KEY_PROMPT])
        assertNull(meta[Object3dNodeMeta.KEY_PROVIDER])
    }

    @Test fun `metadata for a cloud generation carries both provider and prompt`() {
        val meta = Object3dNodeMeta.metadata(
            relativePath = "ef01.glb",
            format = "GLB",
            source = Object3dSource.CLOUD_GENERATED,
            provider = Object3dCloudProvider.MESHY,
            prompt = "a dragon",
        )
        assertEquals("MESHY", meta[Object3dNodeMeta.KEY_PROVIDER])
        assertEquals("a dragon", meta[Object3dNodeMeta.KEY_PROMPT])
        assertEquals("CLOUD_GENERATED", meta[Object3dNodeMeta.KEY_SOURCE])
    }

    @Test fun `metadata for an import carries neither provider nor prompt`() {
        val meta = Object3dNodeMeta.metadata(
            relativePath = "9988.stl",
            format = "STL_BINARY",
            source = Object3dSource.IMPORTED,
            provider = null,
            prompt = null,
        )
        assertEquals("IMPORTED", meta[Object3dNodeMeta.KEY_SOURCE])
        assertNull(meta[Object3dNodeMeta.KEY_PROVIDER])
        assertNull(meta[Object3dNodeMeta.KEY_PROMPT])
        assertFalse(meta.containsKey(Object3dNodeMeta.KEY_PROMPT))
    }

    @Test fun `a blank prompt is omitted, not stored as an empty string`() {
        val meta = Object3dNodeMeta.metadata("x.obj", "OBJ", Object3dSource.ON_DEVICE_GENERATED, null, "   ")
        assertFalse(meta.containsKey(Object3dNodeMeta.KEY_PROMPT))
    }
}
