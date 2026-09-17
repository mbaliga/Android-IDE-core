package dev.fonebrew.data

import android.content.Context
import dev.fonebrew.domain.object3d.Object3dCloudProvider
import java.io.File
import java.security.MessageDigest

/**
 * docs/design/objects-3d.md §1/§8's "preview anything's output" storage: which chat turn produced
 * an object3d node — the mini-chooser's on-device path, a watched-cloud provider, or a plain SAF
 * import. Mirrors [dev.fonebrew.domain.tree.Conversations.IMAGE_KEY]'s image-turn discrimination,
 * but 3D turns need a *source*, not just a presence flag, because the viewer/retry/export
 * surfaces all care which path produced the file.
 */
enum class Object3dSource {
    ON_DEVICE_GENERATED,
    CLOUD_GENERATED,
    IMPORTED,
}

/**
 * Pure node-minting helpers for an object3d turn — no Android, no I/O, JVM-tested. A turn's
 * payload is a file under [Object3dStore]'s directory; [metadata] is the one place that builds
 * the flat [dev.fonebrew.domain.MessageNode.metadata] map a caller hands to
 * [dev.fonebrew.domain.tree.Nodes.child] at INSERT time — append-only, per CLAUDE.md's tree
 * spine: this map is built once and never edited after the node lands.
 *
 * Mirrors schemas/object3d/object3d-node.schema.json's flat field names
 * (`object3d.file|format|source|provider|prompt`) one-for-one, the same "one string key per
 * schema field" shape [dev.fonebrew.domain.tree.Conversations.IMAGE_KEY] and the cost fields
 * (`costMinor`/`tokensIn`/`tokensOut` — see `ChatScreen.kt`'s `MessageTurn`) already use for a
 * turn's sidecar data on the plain `Map<String, String>` metadata bag.
 */
object Object3dNodeMeta {

    const val KEY_FILE = "object3d.file"
    const val KEY_FORMAT = "object3d.format"
    const val KEY_SOURCE = "object3d.source"
    const val KEY_PROVIDER = "object3d.provider"
    const val KEY_PROMPT = "object3d.prompt"

    /** §4's on-device primitive-DSL scenes are stored as their own JSON text, not one of §2's
     *  renderable container formats — this is the [KEY_FORMAT] sentinel for that case (a caller
     *  reading it back decodes with [dev.fonebrew.domain.object3d.ProceduralSceneCodec], not one
     *  of the three.js container loaders). Never collides with a real
     *  [dev.fonebrew.domain.object3d.Object3dFormat] enum name. */
    const val DSL_JSON_FORMAT = "DSL_JSON"

    /** File extension [Object3dStore.save] should use for a [DSL_JSON_FORMAT] payload. */
    const val DSL_JSON_EXTENSION = "json"

    fun isObject3dNode(metadata: Map<String, String>): Boolean = metadata.containsKey(KEY_FILE)

    /**
     * Build the metadata bag for one object3d turn. [format] is either a
     * [dev.fonebrew.domain.object3d.Object3dFormat.name] (a real file) or [DSL_JSON_FORMAT].
     * [provider]/[prompt] are omitted (not merely blank) when null, matching every other
     * optional-field codec in this corpus (`org.json`'s own `put`-only-if-present discipline —
     * see e.g. [dev.fonebrew.domain.object3d.Object3dJobCodec.encode]) — an IMPORTED turn
     * genuinely has neither.
     */
    fun metadata(
        relativePath: String,
        format: String,
        source: Object3dSource,
        provider: Object3dCloudProvider?,
        prompt: String?,
    ): Map<String, String> = buildMap {
        put(KEY_FILE, relativePath)
        put(KEY_FORMAT, format)
        put(KEY_SOURCE, source.name)
        provider?.let { put(KEY_PROVIDER, it.name) }
        prompt?.takeIf { it.isNotBlank() }?.let { put(KEY_PROMPT, it) }
    }
}

/**
 * App-private `objects3d/` file storage (docs/design/objects-3d.md §8) — the [Object3dNodeMeta]-
 * described payload's home. Hash-named (unlike [ImageStore]'s UUID-named files): the same bytes
 * saved twice collapse to one file, which matters here because a cloud re-download or a re-import
 * of the same source file is a realistic repeat, not just a theoretical one.
 *
 * No cloud copy is ever kept beyond the one download (binding rule 1) — this store only ever
 * holds what already landed on-device; it has no network client of its own.
 */
class Object3dStore(context: Context) {
    val dir: File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** Save [bytes] under a content-hashed name; returns the relative (bare-filename) path a
     *  caller hands to [Object3dNodeMeta.metadata]'s `relativePath`. Idempotent: re-saving
     *  identical bytes returns the same name without rewriting the file. */
    fun save(bytes: ByteArray, extension: String): String {
        val name = fileNameFor(bytes, extension)
        val file = File(dir, name)
        if (!file.exists()) file.writeBytes(bytes)
        return name
    }

    fun readBytes(relativePath: String): ByteArray = resolve(relativePath).readBytes()

    fun exists(relativePath: String): Boolean = runCatching { resolve(relativePath).exists() }.getOrDefault(false)

    fun list(): List<File> = dir.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun delete(relativePath: String) {
        runCatching { resolve(relativePath).delete() }
    }

    /** [relativePath] must be a bare file name — schemas/object3d/object3d-node.schema.json's
     *  `StoredFileRef.relativePath` $comment's path-traversal obligation, enforced here as the
     *  resolver-side check that $comment says a single-document JSON Schema cannot itself make
     *  (mirrors [dev.fonebrew.domain.mirror]'s and the Git-API layer's own resolver-side checks
     *  for the same class of gap). */
    private fun resolve(relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.contains('/') && !relativePath.contains('\\') && !relativePath.contains("..")) {
            "Object3dStore: refusing relativePath '$relativePath' — must be a bare file name, no path separators or '..'"
        }
        return File(dir, relativePath)
    }

    companion object {
        const val DIR_NAME = "objects3d"

        /** SHA-256 hex digest of [bytes] + `.` + [extension] — pure, no Android, so this half of
         *  the save logic is directly JVM-testable without a [Context]. */
        internal fun fileNameFor(bytes: ByteArray, extension: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "$hex.$extension"
        }
    }
}
