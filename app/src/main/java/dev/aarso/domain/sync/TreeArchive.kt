package dev.aarso.domain.sync

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import org.json.JSONObject

/**
 * The open, diff-friendly on-disk format for the message tree — the foundation of
 * tree-sovereignty (docs/design/tree-sovereignty.md). The whole tree is a set of
 * files the user can keep in a Git repo they own; because nodes are append-only
 * and uniquely identified, files are created and never edited, and merging is a
 * conflict-free union.
 *
 * This is a *contract*: the format is documented so any tool can read a user's
 * history (the "discard the shell" promise). Bump [FORMAT_VERSION] only with a
 * migration. Pure domain (org.json) — no Android, JVM-testable.
 *
 *   aarso/manifest.json        { "format": "aarso-tree", "version": 1 }
 *   aarso/nodes/<id>.json       one node each
 */
object TreeArchive {

    const val FORMAT_VERSION = 1
    const val MANIFEST_PATH = "aarso/manifest.json"
    private const val NODES_DIR = "aarso/nodes/"

    /** Serialize [nodes] to a map of repo-relative path → file content. */
    fun write(nodes: List<MessageNode>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out[MANIFEST_PATH] = JSONObject()
            .put("format", "aarso-tree")
            .put("version", FORMAT_VERSION)
            .toString(2)
        for (n in nodes) out["$NODES_DIR${n.id}.json"] = nodeToJson(n)
        return out
    }

    /** Read back every node file in [files] (ignores the manifest and anything else). */
    fun read(files: Map<String, String>): List<MessageNode> =
        files.entries
            .filter { it.key.startsWith(NODES_DIR) && it.key.endsWith(".json") }
            .map { nodeFromJson(it.value) }

    private fun nodeToJson(n: MessageNode): String {
        val obj = JSONObject()
        obj.put("id", n.id)
        obj.put("parentId", n.parentId ?: JSONObject.NULL)
        obj.put("role", n.role.wire)
        obj.put("content", n.content)
        obj.put("modelId", n.modelId ?: JSONObject.NULL)
        obj.put("createdAt", n.createdAt)
        obj.put("tokenCounts", JSONObject().apply { n.tokenCounts.forEach { (k, v) -> put(k, v) } })
        obj.put("metadata", JSONObject().apply { n.metadata.forEach { (k, v) -> put(k, v) } })
        return obj.toString(2)
    }

    private fun nodeFromJson(json: String): MessageNode {
        val obj = JSONObject(json)
        val tokenCounts = LinkedHashMap<String, Int>()
        obj.optJSONObject("tokenCounts")?.let { tc -> tc.keys().forEach { tokenCounts[it] = tc.getInt(it) } }
        val metadata = LinkedHashMap<String, String>()
        obj.optJSONObject("metadata")?.let { md -> md.keys().forEach { metadata[it] = md.getString(it) } }
        return MessageNode(
            id = obj.getString("id"),
            parentId = if (obj.isNull("parentId")) null else obj.getString("parentId"),
            role = Role.fromWire(obj.getString("role")),
            content = obj.getString("content"),
            modelId = if (obj.isNull("modelId")) null else obj.getString("modelId"),
            createdAt = obj.getLong("createdAt"),
            tokenCounts = tokenCounts,
            metadata = metadata,
        )
    }
}
