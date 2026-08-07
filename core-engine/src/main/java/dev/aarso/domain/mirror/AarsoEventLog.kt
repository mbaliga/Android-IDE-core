package dev.aarso.domain.mirror

import org.json.JSONObject

/** Where an [AarsoEvent] line actually gets written. Kept as a pure interface so [AarsoEventLog] stays testable without real file I/O — the on-device implementation ([dev.aarso.data.AarsoFileEventSink]) is the only piece that touches a filesystem. */
fun interface AarsoEventSink {
    suspend fun append(line: String)
}

/**
 * Per-signal capture toggles (STUDIO_UX_SPEC.md §10: *"Capture is off by default, per-signal
 * toggles, visible recording glyph when on."*). Every kind defaults to `false` — nothing is
 * captured until the user explicitly turns a signal on; there is no "capture everything" switch.
 */
data class AarsoCaptureSettings(
    val enabled: Set<AarsoEventKind> = emptySet(),
) {
    fun isEnabled(kind: AarsoEventKind): Boolean = kind in enabled

    companion object {
        /** Nothing captured — the honest default this ships with. */
        val OFF = AarsoCaptureSettings(emptySet())
    }
}

/**
 * The append-only Aarso event log's write side (STUDIO_UX_SPEC.md §10). This class is
 * deliberately the *entire* implementation for now: check the per-signal toggle, serialize, and
 * append one JSONL line — nothing here reads the log back, computes anything from it, or
 * displays anything. That's the whole of what's allowed to exist before Issue #2's methodology
 * is ratified (CLAUDE.md rule 4).
 */
class AarsoEventLog(
    private val sink: AarsoEventSink,
    private val settings: () -> AarsoCaptureSettings,
) {
    /**
     * Records one event if [kind] is enabled in the current [AarsoCaptureSettings]; a no-op
     * otherwise (silently skipped, not queued — a later-enabled toggle does not retroactively
     * capture what happened while it was off).
     */
    suspend fun record(kind: AarsoEventKind, payloadJson: String, sessionRef: String? = null, now: Long) {
        if (!settings().isEnabled(kind)) return
        val event = AarsoEvent(t = now, kind = kind, payloadJson = payloadJson, sessionRef = sessionRef)
        sink.append(serialize(event))
    }

    companion object {
        /** One JSON object per line (JSONL), matching the spec's `aarso/events.jsonl` shape exactly. [AarsoEvent.payloadJson] is embedded as a raw JSON value (via [JSONObject]'s own parser), not double-escaped as a string. */
        fun serialize(event: AarsoEvent): String {
            val obj = JSONObject()
            obj.put("t", event.t)
            obj.put("kind", event.kind.wire)
            obj.put("payload", JSONObject(event.payloadJson))
            if (event.sessionRef != null) obj.put("sessionRef", event.sessionRef)
            return obj.toString()
        }
    }
}
