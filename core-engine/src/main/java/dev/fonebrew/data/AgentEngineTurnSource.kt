package dev.fonebrew.data

import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.agent.AgentMessage
import dev.fonebrew.domain.agent.AgentTurnSource
import dev.fonebrew.domain.agent.CancelToken
import dev.fonebrew.domain.agent.TurnLimits
import dev.fonebrew.domain.agent.TurnReply
import dev.fonebrew.domain.agent.TurnUsage
import dev.fonebrew.domain.cost.UsagePricing
import dev.fonebrew.domain.ledger.LedgerEntry
import dev.fonebrew.domain.ledger.Tier
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.inference.InferenceEngine
import kotlinx.coroutines.flow.transformWhile

/**
 * Adapts an [InferenceEngine] to the agent loop's one model seam, and writes one `surface = agent`
 * ledger row per model call through the existing [AgentRepoRunner.recordAgentUsage] (cloud priced
 * and flagged, on-device zero). The engine must already be loaded. A failed or cancelled call
 * writes no row: that is today's behaviour, and whether it should become an error row is an owner
 * question (see docs/design/agent-multistep.md), deliberately not decided here.
 */
class AgentEngineTurnSource(
    private val engine: InferenceEngine,
    private val spec: ModelSpec,
    private val cancel: CancelToken,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { java.util.UUID.randomUUID().toString() },
    private val appendLedger: suspend (LedgerEntry) -> Unit = {},
    private val priceFor: (String) -> UsagePricing,
    private val recordFreeTier: (providerId: String, tokensIn: Long, tokensOut: Long) -> Unit = { _, _, _ -> },
) : AgentTurnSource {

    override suspend fun next(messages: List<AgentMessage>, limits: TurnLimits): TurnReply {
        val startedAt = clock()
        val nodes = ArrayList<MessageNode>(messages.size)
        messages.forEachIndexed { i, m ->
            nodes += MessageNode("agent-$i", if (i == 0) null else "agent-${i - 1}", m.role, m.text, createdAt = startedAt + i)
        }
        val sb = StringBuilder()
        var tokens = 0
        engine.generate(nodes, SamplingParams()).transformWhile<GeneratedToken, Unit> {
            sb.append(it.text)
            tokens++
            !(cancel.isCancelled || tokens >= limits.maxOutputTokens || sb.length >= limits.maxOutputBytes)
        }.collect { }
        val raw = sb.toString()
        var captured: LedgerEntry? = null
        // A ledger failure must never fail the step itself.
        runCatching {
            AgentRepoRunner.recordAgentUsage(
                spec = spec,
                tokenizerId = engine.tokenizerId,
                reported = (engine as? dev.fonebrew.inference.cloud.CloudEngine)?.lastUsage,
                countTokens = { engine.countTokens(it) },
                promptText = messages.joinToString("\n") { it.text },
                outputText = raw,
                startedAt = startedAt,
                finishedAt = clock(),
                runId = idGen(),
                appendLedger = { e -> captured = e; appendLedger(e) },
                priceFor = priceFor,
                recordFreeTier = recordFreeTier,
            )
        }
        val e = captured
        val usage = e?.let {
            TurnUsage(
                it.inputTokens, it.outputTokens, it.estimated,
                costMinor = if (it.tier == Tier.ON_DEVICE) 0L else it.estCostMinor,
            )
        }
        return TurnReply(raw, usage)
    }
}
