package dev.fonebrew.data.search.semantic

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.fonebrew.domain.search.semantic.DocManifest
import dev.fonebrew.domain.search.semantic.VectorEntry
import dev.fonebrew.domain.search.semantic.VectorStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [VectorStore] over a small SQLite file of its own (docs/design/semantic-search.md section 5),
 * separate from the FTS5 `SearchDatabase` and from Room. Raw statements through the bundled
 * driver already in the build -- no SQLDelight database, no `Search.sq` involvement.
 *
 * Vectors are little-endian float32 BLOBs keyed (doc_id, chunk, embedder_id). A doc is complete
 * only through its `doc_manifest` row, written in the same transaction as its chunks.
 * App-private, never uploaded; [clear] empties it.
 */
class SqliteVectorStore private constructor(private val conn: SQLiteConnection) : VectorStore {

    private val lock = Any()

    init {
        synchronized(lock) {
            conn.execSQL("PRAGMA journal_mode = WAL")
            conn.execSQL(
                "CREATE TABLE IF NOT EXISTS doc_manifest(" +
                    "doc_id TEXT PRIMARY KEY, embedder_id TEXT NOT NULL, content_hash TEXT NOT NULL, " +
                    "chunk_count INTEGER NOT NULL, dim INTEGER NOT NULL, indexed_at INTEGER NOT NULL)",
            )
            conn.execSQL(
                "CREATE TABLE IF NOT EXISTS chunk_vectors(" +
                    "doc_id TEXT NOT NULL, chunk INTEGER NOT NULL, embedder_id TEXT NOT NULL, " +
                    "dim INTEGER NOT NULL, vec BLOB NOT NULL, PRIMARY KEY(doc_id, chunk, embedder_id))",
            )
            conn.execSQL("CREATE INDEX IF NOT EXISTS idx_cv_embedder ON chunk_vectors(embedder_id)")
        }
    }

    override fun manifests(): List<DocManifest> = synchronized(lock) {
        val out = ArrayList<DocManifest>()
        conn.prepare("SELECT doc_id, embedder_id, content_hash, chunk_count, dim FROM doc_manifest").use { st ->
            while (st.step()) {
                out.add(DocManifest(st.getText(0), st.getText(1), st.getText(2), st.getLong(3).toInt(), st.getLong(4).toInt()))
            }
        }
        out
    }

    override fun replaceDoc(manifest: DocManifest, vectors: List<FloatArray>) = synchronized(lock) {
        conn.execSQL("BEGIN IMMEDIATE")
        try {
            deleteDoc(manifest.docId)
            conn.prepare("INSERT INTO chunk_vectors(doc_id, chunk, embedder_id, dim, vec) VALUES (?,?,?,?,?)").use { st ->
                vectors.forEachIndexed { i, v ->
                    st.reset()
                    st.bindText(1, manifest.docId)
                    st.bindLong(2, i.toLong())
                    st.bindText(3, manifest.embedderId)
                    st.bindLong(4, v.size.toLong())
                    st.bindBlob(5, encode(v))
                    st.step()
                }
            }
            conn.prepare("INSERT INTO doc_manifest(doc_id, embedder_id, content_hash, chunk_count, dim, indexed_at) VALUES (?,?,?,?,?,?)").use { st ->
                st.bindText(1, manifest.docId)
                st.bindText(2, manifest.embedderId)
                st.bindText(3, manifest.contentHash)
                st.bindLong(4, vectors.size.toLong())
                st.bindLong(5, manifest.dim.toLong())
                st.bindLong(6, System.currentTimeMillis())
                st.step()
            }
            conn.execSQL("COMMIT")
        } catch (t: Throwable) {
            runCatching { conn.execSQL("ROLLBACK") }
            throw t
        }
    }

    override fun removeDoc(docId: String) = synchronized(lock) { deleteDoc(docId) }

    private fun deleteDoc(docId: String) {
        conn.prepare("DELETE FROM chunk_vectors WHERE doc_id = ?").use { it.bindText(1, docId); it.step() }
        conn.prepare("DELETE FROM doc_manifest WHERE doc_id = ?").use { it.bindText(1, docId); it.step() }
    }

    /** Keyset-paged on rowid so the lock is held per page, never across the caller's iteration. */
    override fun entries(embedderId: String): Sequence<VectorEntry> = sequence {
        var after = 0L
        while (true) {
            val page = ArrayList<VectorEntry>(PAGE)
            var last = after
            synchronized(lock) {
                conn.prepare("SELECT rowid, doc_id, chunk, dim, vec FROM chunk_vectors WHERE embedder_id = ? AND rowid > ? ORDER BY rowid LIMIT $PAGE").use { st ->
                    st.bindText(1, embedderId)
                    st.bindLong(2, after)
                    while (st.step()) {
                        last = st.getLong(0)
                        val dim = st.getLong(3).toInt()
                        val blob = st.getBlob(4)
                        if (blob.size == dim * 4) page.add(VectorEntry(st.getText(1), st.getLong(2).toInt(), decode(blob)))
                    }
                }
            }
            if (last == after) break
            after = last
            yieldAll(page)
        }
    }

    override fun clear() {
        synchronized(lock) {
            conn.execSQL("DELETE FROM chunk_vectors")
            conn.execSQL("DELETE FROM doc_manifest")
            conn.execSQL("VACUUM")
        }
    }

    override fun bytesUsed(): Long = synchronized(lock) {
        var pages = 0L
        var size = 0L
        conn.prepare("PRAGMA page_count").use { if (it.step()) pages = it.getLong(0) }
        conn.prepare("PRAGMA page_size").use { if (it.step()) size = it.getLong(0) }
        pages * size
    }

    fun close() = synchronized(lock) { conn.close() }

    companion object {
        private const val PAGE = 256

        /** Opens (creating) the store at [file]. A file SQLite cannot read is replaced by an empty store: the index is derived data and rebuilds. */
        fun open(file: File): SqliteVectorStore {
            file.parentFile?.mkdirs()
            return try {
                SqliteVectorStore(BundledSQLiteDriver().open(file.absolutePath))
            } catch (e: Exception) {
                listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
                SqliteVectorStore(BundledSQLiteDriver().open(file.absolutePath))
            }
        }

        fun encode(v: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (x in v) b.putFloat(x)
            return b.array()
        }

        fun decode(blob: ByteArray): FloatArray {
            val b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(blob.size / 4) { b.getFloat() }
        }
    }
}

/**
 * Defers opening the SQLite file until something reads or writes it, so an app that never turns
 * semantic search on never creates the store (the service object exists from the first search).
 */
class LazyVectorStore(private val file: File) : VectorStore {
    private val real: SqliteVectorStore by lazy { SqliteVectorStore.open(file) }
    @Volatile private var opened = false
    private fun r(): SqliteVectorStore { opened = true; return real }

    override fun manifests() = r().manifests()
    override fun replaceDoc(manifest: DocManifest, vectors: List<FloatArray>) = r().replaceDoc(manifest, vectors)
    override fun removeDoc(docId: String) = r().removeDoc(docId)
    override fun entries(embedderId: String) = r().entries(embedderId)
    override fun clear() { if (opened || file.exists()) r().clear() }
    override fun bytesUsed(): Long = if (opened) real.bytesUsed() else if (file.exists()) file.length() else 0L
}
