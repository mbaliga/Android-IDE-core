package dev.aarso.domain.ledger

/**
 * **The usage ledger** (Doc 01 §10 + Doc 07 — the "Myself" usage views).
 *
 * On-thesis cognitive sovereignty means you can *see* what the machine did on your
 * behalf: how many tokens went where, what it cost, and — crucially — **how much of
 * your thinking stayed on your own device**. The ledger is the append-only record that
 * makes that legible. The writer drops **one [LedgerEntry] per turn**, and **one per
 * Council member** when a single round fans out to multiple agents (so a council turn
 * is several entries that share a `chatId` and a near-identical `timestampMillis`).
 *
 * This is a *pure record*, not a metric verdict: every field is a fact observed at the
 * moment of generation. The "Myself" surfaces in Doc 07 are then **pure on-device
 * aggregations** over `List<LedgerEntry>` (see [LedgerAggregations]) — no telemetry, no
 * phoning home, no server-side rollup. The aggregation math lives entirely here so it
 * stays JVM-testable and the UI is a thin reader over it.
 *
 * Honesty rule (binding): [estCostMinor] is an *estimate* unless the provider returned
 * real usage; [estimated] records which. Aggregations surface the estimated share so a
 * cost figure is never silently presented as measured truth.
 *
 * Pure domain, JVM-tested. No Android, no clock, no I/O — `timestampMillis` is supplied
 * by the writer, never read from `System.currentTimeMillis()` in here.
 */
data class LedgerEntry(
    /** Epoch millis the turn completed at — supplied by the writer, never read here. */
    val timestampMillis: Long,
    /** Project this chat belongs to, or `null` for a loose chat outside any project. */
    val projectId: String?,
    /** The chat (message-tree root) this turn lives in. Council entries share it. */
    val chatId: String,
    /** The message-tree node this entry records the cost of. Unique per entry. */
    val nodeId: String,
    /** Model identifier as the engine saw it (e.g. `"qwen2.5-7b"`, `"claude-…"`). */
    val model: String,
    /** Provider/vendor label (e.g. `"on-device"`, `"anthropic"`, `"openai-compat"`). */
    val provider: String,
    /** Where the work physically ran. A single turn is [Provenance.LOCAL] or [Provenance.CLOUD]. */
    val provenance: Provenance,
    /** Single agent, or which kind of Council fan-out produced this entry. */
    val interactionModel: InteractionModel,
    /** The Council member this entry is for, or `null` for a plain single-agent turn. */
    val councilMemberId: String?,
    /** Prompt/input tokens billed or counted for this turn. */
    val inputTokens: Long,
    /** Completion/output tokens for this turn. */
    val outputTokens: Long,
    /** Estimated money cost in the smallest currency unit (paise / cents). 0 on-device. */
    val estCostMinor: Long,
    /** Wall-clock latency of the turn in milliseconds (observed, not billed). */
    val latencyMs: Long,
    /** Which execution tier carried the turn — finer than [provenance]. */
    val tier: Tier,
    /** How the turn ended — completed, user-stopped, or errored. */
    val status: Status,
    /** `true` if [estCostMinor] is a guess; `false` if it came from real provider usage. */
    val estimated: Boolean,
) {
    /** Input + output tokens for this single turn. */
    val totalTokens: Long get() = inputTokens + outputTokens
}

/**
 * Where the work ran. A *single turn* is only ever [LOCAL] or [CLOUD]; [MIXED] is a
 * value used by **aggregates** (e.g. a model that has both on-device and cloud entries,
 * or a whole-period rollup that spans both).
 */
enum class Provenance { LOCAL, CLOUD, MIXED }

/** Single agent, or which Council fan-out shape produced the turn. */
enum class InteractionModel { SINGLE, COUNCIL_PERSONAS, COUNCIL_MODELS }

/** The execution tier that carried the turn — finer-grained than [Provenance]. */
enum class Tier { ON_DEVICE, RUNNER, CLOUD }

/** How a turn ended. [STOPPED] = user cancelled; [ERROR] = engine/provider failure. */
enum class Status { COMPLETE, STOPPED, ERROR }
