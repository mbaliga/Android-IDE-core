package dev.fonebrew.domain.workspace.files

import dev.fonebrew.contracts.common.ErrorEnvelope
import dev.fonebrew.contracts.common.ErrorSeverity
import dev.fonebrew.contracts.common.SideEffectState
import dev.fonebrew.contracts.workspace.BufferConflict
import dev.fonebrew.contracts.workspace.DocumentBufferState
import dev.fonebrew.contracts.workspace.LineEndings
import dev.fonebrew.domain.contracts.Digest
import dev.fonebrew.domain.workspace.DocumentBufferMachine
import dev.fonebrew.domain.workspace.roots.Guarded
import dev.fonebrew.domain.workspace.roots.Workspace
import dev.fonebrew.domain.workspace.roots.WorkspacePath
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** What the registry knows about one open file. It holds NO text: the editor owns the buffer. */
data class OpenDocument(
    val path: WorkspacePath,
    val state: DocumentBufferState,
    /** SHA-256 hex of the bytes last read or written; the `expectedRevision` of the next save (FB-RAT-WS-004). */
    val baseRevision: String,
    val lineEndings: LineEndings,
    val hasBom: Boolean,
    /** Disk bytes differ from [baseRevision] but the buffer is clean: a reload is offered, never forced. */
    val staleOnDisk: Boolean = false,
    val missingOnDisk: Boolean = false,
    val lastReportedDiskRevision: String? = null,
) {
    val dirty: Boolean get() = state !is DocumentBufferState.OpenClean && state !is DocumentBufferState.Closed
}

/** The typed event for "the file changed on disk behind an open document". */
data class ExternalChange(
    val path: WorkspacePath,
    val kind: Kind,
    /** True = unsaved edits exist, so this is a conflict the user must resolve; false = safe to offer a reload. */
    val whileDirty: Boolean,
    val diskRevision: String?,
) { enum class Kind { MODIFIED, DELETED } }

enum class OpenRefusal { NOT_FOUND, NOT_A_FILE, PATH_ESCAPE, BINARY, TOO_LARGE, NOT_UTF8, ALREADY_OPEN }

sealed interface OpenResult {
    /** [text] is the decoded content with its ORIGINAL line endings and without a BOM. */
    data class Opened(val doc: OpenDocument, val text: String) : OpenResult
    data class Refused(val reason: OpenRefusal, val message: String) : OpenResult
}

sealed interface SaveResult {
    data class Saved(val doc: OpenDocument, val atomic: Boolean, val modePreserved: Boolean) : SaveResult
    /** The disk copy changed since open; nothing was written. Retry with `overwrite = true` to take the user's decision. */
    data class Conflict(val doc: OpenDocument, val diskRevision: String) : SaveResult
    data class NotOpen(val path: WorkspacePath) : SaveResult
    data class Failed(val doc: OpenDocument, val message: String) : SaveResult
}

/**
 * Path to open-document state with a dirty flag, a last-saved revision hash and external-change
 * detection, consistent with WORKSPACE_KERNEL_SPEC.md: state is a [DocumentBufferState] and every
 * transition is validated by [DocumentBufferMachine] (so a save while clean, or a conflict from a
 * closed buffer, is rejected the same way the kernel rejects it). Thread-safe.
 *
 * Saving is an atomic temp+rename write ([AtomicFileWriter]) that keeps the file's permission bits
 * and its line-ending convention: text handed to [save] is normalised to `\n` and rewritten with
 * the document's own LF / CRLF / CR. A file that was MIXED on open is written exactly as given
 * (silently normalising it would change lines the user never touched); the document stays MIXED
 * and the result says so by [OpenDocument.lineEndings]. A UTF-8 BOM, if present on open, is kept.
 * Only UTF-8 text is supported: other encodings and binary files are refused at open.
 */
class OpenDocuments(
    private val workspace: Workspace,
    private val clock: () -> Instant = Instant::now,
) {
    private val lock = Any()
    private val docs = LinkedHashMap<WorkspacePath, OpenDocument>()

    fun get(path: WorkspacePath): OpenDocument? = synchronized(lock) { docs[path] }
    fun all(): List<OpenDocument> = synchronized(lock) { docs.values.toList() }

    fun open(path: WorkspacePath): OpenResult = synchronized(lock) {
        if (docs.containsKey(path)) return OpenResult.Refused(OpenRefusal.ALREADY_OPEN, "'${path.rel}' is already open")
        val real = real(path) ?: return OpenResult.Refused(OpenRefusal.PATH_ESCAPE, "path refused")
        if (!Files.exists(real)) return OpenResult.Refused(OpenRefusal.NOT_FOUND, "'${path.rel}' does not exist")
        if (!Files.isRegularFile(real)) return OpenResult.Refused(OpenRefusal.NOT_A_FILE, "'${path.rel}' is not a file")
        if (Files.size(real) > MAX_BYTES) return OpenResult.Refused(OpenRefusal.TOO_LARGE, "larger than $MAX_BYTES bytes")
        val bytes = Files.readAllBytes(real)
        if (looksBinary(bytes)) return OpenResult.Refused(OpenRefusal.BINARY, "looks like a binary file")
        val hasBom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val text = try { decodeStrict(bytes, if (hasBom) 3 else 0) } catch (_: CharacterCodingException) {
            return OpenResult.Refused(OpenRefusal.NOT_UTF8, "not valid UTF-8")
        }
        var doc = OpenDocument(path, DocumentBufferState.Closed, Digest.of(bytes).digestHex, detectLineEndings(text), hasBom)
        doc = doc.copy(state = apply(doc.state, DocumentBufferMachine.Event.Open))
        docs[path] = doc
        OpenResult.Opened(doc, text)
    }

    /** The editor reports an edit. Idempotent while already dirty. */
    fun markEdited(path: WorkspacePath): OpenDocument? = synchronized(lock) {
        val d = docs[path] ?: return null
        val next = when (d.state) {
            is DocumentBufferState.OpenClean, is DocumentBufferState.SaveFailed -> apply(d.state, DocumentBufferMachine.Event.Edit)
            else -> d.state
        }
        d.copy(state = next).also { docs[path] = it }
    }

    /**
     * Writes [text]. Refuses with [SaveResult.Conflict] when the disk revision differs from the
     * document's base revision, unless [overwrite] (the user's explicit decision) is set.
     */
    fun save(path: WorkspacePath, text: String, overwrite: Boolean = false): SaveResult = synchronized(lock) {
        var d = docs[path] ?: return SaveResult.NotOpen(path)
        val real = real(path) ?: return SaveResult.Failed(d, "path refused")
        // clean (or failed-save) documents are brought to dirty so the kernel table is followed
        d = prepareForSave(d)
        val diskRev = if (Files.isRegularFile(real)) Digest.of(Files.readAllBytes(real)).digestHex else null
        if (diskRev != null && diskRev != d.baseRevision && !overwrite) {
            val conflicted = when (d.state) {
                is DocumentBufferState.OpenDirty ->
                    apply(d.state, DocumentBufferMachine.Event.RemoteRevisionChanged(BufferConflict(diskRev, clock(), "changed on disk since it was opened")))
                else -> d.state
            }
            d = d.copy(state = conflicted, lastReportedDiskRevision = diskRev)
            docs[path] = d
            return SaveResult.Conflict(d, diskRev)
        }
        if (d.state is DocumentBufferState.Conflicted) d = resolveToDirty(d)
        d = d.copy(state = apply(d.state, DocumentBufferMachine.Event.SaveRequested))
        val bytes = encode(text, d)
        return try {
            val w = AtomicFileWriter.write(real, bytes)
            d = d.copy(
                state = apply(d.state, DocumentBufferMachine.Event.SaveSucceeded),
                baseRevision = Digest.of(bytes).digestHex, staleOnDisk = false, missingOnDisk = false, lastReportedDiskRevision = null,
            )
            docs[path] = d
            SaveResult.Saved(d, w.atomic, w.modePreserved)
        } catch (e: IOException) {
            val err = ErrorEnvelope(
                code = "WORKSPACE_SAVE_FAILED", severity = ErrorSeverity.ERROR, retryable = true, sideEffectState = SideEffectState.NONE,
                detail = "save of '${path.rel}' failed: ${e.javaClass.simpleName}",
                recoveryAction = "Retry the save, or save a copy elsewhere.",
                userMessage = "Could not save '${path.name}'. Your edits are still open.",
            )
            d = d.copy(state = apply(d.state, DocumentBufferMachine.Event.SaveFailed(err)))
            docs[path] = d
            SaveResult.Failed(d, err.userMessage)
        }
    }

    /**
     * Compares the disk with the document and returns the event to show, or null when nothing new
     * happened. The same disk state is reported once (not on every poll). A dirty document moves to
     * Conflicted; a clean one is only flagged [OpenDocument.staleOnDisk] / [OpenDocument.missingOnDisk].
     */
    fun checkExternal(path: WorkspacePath): ExternalChange? = synchronized(lock) {
        var d = docs[path] ?: return null
        val real = real(path)
        val exists = real != null && Files.isRegularFile(real)
        if (!exists) {
            if (d.missingOnDisk) return null
            docs[path] = d.copy(missingOnDisk = true)
            return ExternalChange(path, ExternalChange.Kind.DELETED, d.dirty, null)
        }
        if (Files.size(real!!) > MAX_BYTES * 8) return null
        val rev = Digest.of(Files.readAllBytes(real)).digestHex
        if (d.missingOnDisk) d = d.copy(missingOnDisk = false)
        if (rev == d.baseRevision) { docs[path] = d.copy(staleOnDisk = false, lastReportedDiskRevision = null); return null }
        if (rev == d.lastReportedDiskRevision) { docs[path] = d; return null }
        val wasDirty = d.dirty
        d = if (d.state is DocumentBufferState.OpenDirty) {
            d.copy(state = apply(d.state, DocumentBufferMachine.Event.RemoteRevisionChanged(BufferConflict(rev, clock(), "changed on disk while edited"))), lastReportedDiskRevision = rev)
        } else d.copy(staleOnDisk = true, lastReportedDiskRevision = rev)
        docs[path] = d
        ExternalChange(path, ExternalChange.Kind.MODIFIED, wasDirty, rev)
    }

    /** Re-reads the file from disk, dropping the document's state and any edits. The caller made that choice. */
    fun reload(path: WorkspacePath): OpenResult = synchronized(lock) {
        docs.remove(path)
        open(path)
    }

    /** Closes a clean document; a dirty one needs `discard = true` (FB-RAT-WS-003: kept until explicit save or discard). */
    fun close(path: WorkspacePath, discard: Boolean = false): Boolean = synchronized(lock) {
        val d = docs[path] ?: return true
        if (d.dirty && !discard) return false
        docs.remove(path)
        true
    }

    /** A path was renamed or moved on disk: re-key the document, keeping its state. */
    fun rekey(from: WorkspacePath, to: WorkspacePath) = synchronized(lock) {
        val d = docs.remove(from) ?: return@synchronized
        docs[to] = d.copy(path = to)
    }

    private fun prepareForSave(d: OpenDocument): OpenDocument = when (d.state) {
        is DocumentBufferState.OpenClean, is DocumentBufferState.SaveFailed -> d.copy(state = apply(d.state, DocumentBufferMachine.Event.Edit))
        else -> d
    }

    private fun resolveToDirty(d: OpenDocument): OpenDocument {
        var s = apply(d.state, DocumentBufferMachine.Event.BeginMerge)
        s = apply(s, DocumentBufferMachine.Event.MergeResolved)
        return d.copy(state = s)
    }

    private fun apply(s: DocumentBufferState, e: DocumentBufferMachine.Event): DocumentBufferState =
        when (val r = DocumentBufferMachine.transition(s, e)) {
            is DocumentBufferMachine.Result.Allowed -> r.next
            is DocumentBufferMachine.Result.Rejected -> throw IllegalStateException(r.reason)
        }

    private fun real(p: WorkspacePath): Path? = (workspace.resolve(p) as? Guarded.Ok)?.path

    companion object {
        const val MAX_BYTES = 10L * 1024 * 1024

        fun looksBinary(bytes: ByteArray): Boolean {
            val n = minOf(bytes.size, 8192)
            for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
            return false
        }

        private fun decodeStrict(bytes: ByteArray, offset: Int): String =
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()

        fun detectLineEndings(text: String): LineEndings {
            var crlf = 0; var lf = 0; var cr = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\r') { if (i + 1 < text.length && text[i + 1] == '\n') { crlf++; i++ } else cr++ }
                else if (c == '\n') lf++
                i++
            }
            val kinds = listOf(crlf, lf, cr).count { it > 0 }
            return when {
                kinds > 1 -> LineEndings.MIXED
                crlf > 0 -> LineEndings.CRLF
                cr > 0 -> LineEndings.CR
                else -> LineEndings.LF
            }
        }

        internal fun encode(text: String, d: OpenDocument): ByteArray {
            val body = when (d.lineEndings) {
                LineEndings.MIXED -> text
                else -> {
                    val lf = text.replace("\r\n", "\n").replace('\r', '\n')
                    when (d.lineEndings) { LineEndings.CRLF -> lf.replace("\n", "\r\n"); LineEndings.CR -> lf.replace('\n', '\r'); else -> lf }
                }
            }
            val b = body.toByteArray(Charsets.UTF_8)
            return if (d.hasBom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + b else b
        }
    }
}
