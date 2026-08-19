package dev.fonebrew.domain.thread

/**
 * **WP10** — pure layered layout for a [ThreadGraph]: one **column** per conversation (`rootId`),
 * ordered so a Fork/Spawn child's column sits immediately after the conversation it forked/spawned
 * from — a family reads left-to-right, oldest lineage first, matching the plan's own "chains as
 * columns" placement rule (THREAD_TOPOLOGY_PLAN.md WP10). Within a column, **row** = topological
 * depth along [ThreadEdgeKind.REPLY] edges (Kahn's algorithm — the only edge kind that encodes real
 * turn-to-turn succession; FORK/SPAWN/MARKER_ANCHOR/DELEGATION_ANCHOR/LINEAGE edges connect across
 * or beside columns, not down one).
 *
 * Pure, deterministic (every tie breaks on node/root id, never map/set iteration order),
 * Room/Android/Compose-independent — same "presenter over an already-built snapshot" shape
 * [ThreadGraphProjector] itself uses. `ui/graph/GraphRoom.kt`'s `ThreadMapCanvas` (Compose canvas,
 * pan/zoom, node draw) is the thin, owner-verified renderer over this; JVM tests assert the
 * placement rules directly, no Compose/Robolectric needed.
 *
 * A [ThreadGraph] the append-only tree can never actually produce a cycle in (every REPLY edge
 * points from an earlier-created node to a later one) still can't wedge this: any node the
 * Kahn's-algorithm pass can't resolve (a hypothetical cycle) is placed one row below the deepest
 * row resolved so far, sorted by id — an honest degrade, never an infinite loop or a thrown
 * exception over malformed input.
 */
object ThreadMapLayout {

    /** [column]/[row] are both 0-based grid coordinates — a renderer multiplies by its own
     *  per-cell spacing to get pixels; this file never picks a unit. */
    data class NodePosition(val column: Int, val row: Int)

    data class Layout(
        val positions: Map<String, NodePosition>,
        val columnCount: Int,
        val rowCount: Int,
    )

    /** Empty in, empty out — no fabricated single-column placeholder for a graph with no nodes. */
    fun compute(graph: ThreadGraph): Layout {
        if (graph.nodes.isEmpty()) return Layout(emptyMap(), 0, 0)
        val byId = graph.nodes.associateBy { it.id }

        // ---- Column order: DFS over the rootId lineage tree built from FORK/SPAWN edges ----
        val parentRootOf = HashMap<String, String>() // childRootId -> parentRootId
        for (edge in graph.edges) {
            if (edge.kind != ThreadEdgeKind.FORK && edge.kind != ThreadEdgeKind.SPAWN) continue
            val srcNode = byId[edge.from] ?: continue
            val childRoot = byId[edge.to]?.rootId ?: continue
            parentRootOf[childRoot] = srcNode.rootId
        }
        val allRoots = graph.nodes.mapTo(LinkedHashSet()) { it.rootId }
        val childrenOf: Map<String?, List<String>> = allRoots.groupBy { parentRootOf[it] }
        val topRoots = allRoots.filter { parentRootOf[it] == null }
        fun earliestAt(rootId: String) = graph.nodes.filter { it.rootId == rootId }.minOf { it.at }

        val columnOrder = ArrayList<String>()
        val placedColumns = HashSet<String>()
        fun visit(rootId: String) {
            if (!placedColumns.add(rootId)) return // guards a malformed cyclic lineage pointer
            columnOrder += rootId
            val kids = childrenOf[rootId].orEmpty().sortedWith(compareBy({ earliestAt(it) }, { it }))
            kids.forEach(::visit)
        }
        topRoots.sortedWith(compareBy({ earliestAt(it) }, { it })).forEach(::visit)
        // Any root only reachable via the guarded cycle above still gets a column, deterministically last.
        allRoots.filter { it !in placedColumns }.sorted().forEach { columnOrder += it; placedColumns += it }
        val columnOf = columnOrder.withIndex().associate { (i, r) -> r to i }

        // ---- Row: topological depth along REPLY edges (Kahn's algorithm) ----
        val outEdges = HashMap<String, MutableList<String>>()
        val indegree = HashMap<String, Int>()
        graph.nodes.forEach { indegree[it.id] = 0 }
        for (edge in graph.edges) {
            if (edge.kind != ThreadEdgeKind.REPLY) continue
            if (!byId.containsKey(edge.from) || !byId.containsKey(edge.to)) continue
            outEdges.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
            indegree[edge.to] = (indegree[edge.to] ?: 0) + 1
        }
        val row = HashMap<String, Int>()
        val queue = ArrayDeque<String>()
        graph.nodes.map { it.id }.sorted().forEach { id -> if (indegree[id] == 0) { row[id] = 0; queue.add(id) } }
        val processed = HashSet<String>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!processed.add(id)) continue
            val r = row[id] ?: 0
            outEdges[id].orEmpty().sorted().forEach { next ->
                row[next] = maxOf(row[next] ?: 0, r + 1)
                indegree[next] = (indegree[next] ?: 1) - 1
                if (indegree[next] == 0 && next !in processed) queue.add(next)
            }
        }
        // A REPLY edge into a node that never reaches indegree 0 (a cycle — never produced by the
        // real tree, but this file never trusts that from the outside) still gets a row.
        val fallbackRow = (row.values.maxOrNull() ?: -1) + 1
        graph.nodes.map { it.id }.filter { it !in row }.sorted().forEach { row[it] = fallbackRow }

        // ---- Markers/delegations ride beside the turn they describe: their anchor's row (or 0) ----
        for (node in graph.nodes) {
            if (node.kind == ThreadNodeKind.MARKER || node.kind == ThreadNodeKind.DELEGATION) {
                row[node.id] = node.parentId?.let { row[it] } ?: 0
            }
        }

        val positions = graph.nodes.associate { n ->
            n.id to NodePosition(column = columnOf.getValue(n.rootId), row = row.getValue(n.id))
        }
        return Layout(
            positions = positions,
            columnCount = columnOrder.size,
            rowCount = (row.values.maxOrNull() ?: -1) + 1,
        )
    }
}
