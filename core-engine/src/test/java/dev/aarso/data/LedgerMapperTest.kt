package dev.aarso.data

import dev.aarso.data.entity.LedgerEntryEntity
import dev.aarso.domain.ledger.InteractionModel
import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.domain.ledger.Provenance
import dev.aarso.domain.ledger.Status
import dev.aarso.domain.ledger.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [LedgerMapper] — no Room runtime needed, the mapper is a total function
 * over the two data classes. Covers: field-for-field round-trips both ways, every enum's
 * name↔value mapping, and the nullable fields ([LedgerEntry.projectId] /
 * [LedgerEntry.councilMemberId]).
 */
class LedgerMapperTest {

    /** A representative cloud, single-agent entry with both nullables populated. */
    private val domainEntry = LedgerEntry(
        timestampMillis = 1_700_000_000_000L,
        projectId = "proj-42",
        chatId = "chat-7",
        nodeId = "node-99",
        model = "claude-sonnet",
        provider = "anthropic",
        provenance = Provenance.CLOUD,
        interactionModel = InteractionModel.COUNCIL_PERSONAS,
        councilMemberId = "member-3",
        inputTokens = 1234L,
        outputTokens = 567L,
        estCostMinor = 89L,
        latencyMs = 4321L,
        tier = Tier.CLOUD,
        status = Status.COMPLETE,
        estimated = true,
    )

    @Test
    fun `toEntity stores enums as their name`() {
        val e = LedgerMapper.toEntity(domainEntry)
        assertEquals("CLOUD", e.provenance)
        assertEquals("COUNCIL_PERSONAS", e.interactionModel)
        assertEquals("CLOUD", e.tier)
        assertEquals("COMPLETE", e.status)
    }

    @Test
    fun `toEntity copies every scalar field`() {
        val e = LedgerMapper.toEntity(domainEntry)
        assertEquals(1_700_000_000_000L, e.timestampMillis)
        assertEquals("proj-42", e.projectId)
        assertEquals("chat-7", e.chatId)
        assertEquals("node-99", e.nodeId)
        assertEquals("claude-sonnet", e.model)
        assertEquals("anthropic", e.provider)
        assertEquals("member-3", e.councilMemberId)
        assertEquals(1234L, e.inputTokens)
        assertEquals(567L, e.outputTokens)
        assertEquals(89L, e.estCostMinor)
        assertEquals(4321L, e.latencyMs)
        assertTrue(e.estimated)
    }

    @Test
    fun `toDomain parses enum names back to values`() {
        val entity = LedgerMapper.toEntity(domainEntry)
        val back = LedgerMapper.toDomain(entity)
        assertEquals(Provenance.CLOUD, back.provenance)
        assertEquals(InteractionModel.COUNCIL_PERSONAS, back.interactionModel)
        assertEquals(Tier.CLOUD, back.tier)
        assertEquals(Status.COMPLETE, back.status)
    }

    @Test
    fun `round-trip is field-equal for a fully populated entry`() {
        val back = LedgerMapper.toDomain(LedgerMapper.toEntity(domainEntry))
        // data class equality covers every field at once.
        assertEquals(domainEntry, back)
    }

    @Test
    fun `nullable fields survive the round-trip when null`() {
        val loose = domainEntry.copy(
            projectId = null,
            councilMemberId = null,
            provenance = Provenance.LOCAL,
            interactionModel = InteractionModel.SINGLE,
            tier = Tier.ON_DEVICE,
            estimated = false,
        )
        val entity = LedgerMapper.toEntity(loose)
        assertNull(entity.projectId)
        assertNull(entity.councilMemberId)

        val back = LedgerMapper.toDomain(entity)
        assertNull(back.projectId)
        assertNull(back.councilMemberId)
        assertEquals(loose, back)
    }

    @Test
    fun `local on-device single-agent entry maps correctly`() {
        val local = domainEntry.copy(
            provider = "on-device",
            provenance = Provenance.LOCAL,
            interactionModel = InteractionModel.SINGLE,
            tier = Tier.ON_DEVICE,
            estCostMinor = 0L,
            estimated = false,
        )
        val entity = LedgerMapper.toEntity(local)
        assertEquals("LOCAL", entity.provenance)
        assertEquals("SINGLE", entity.interactionModel)
        assertEquals("ON_DEVICE", entity.tier)
        assertFalse(entity.estimated)
        assertEquals(local, LedgerMapper.toDomain(entity))
    }

    @Test
    fun `every Provenance value round-trips`() {
        for (p in Provenance.entries) {
            val back = LedgerMapper.toDomain(LedgerMapper.toEntity(domainEntry.copy(provenance = p)))
            assertEquals(p, back.provenance)
        }
    }

    @Test
    fun `every InteractionModel value round-trips`() {
        for (im in InteractionModel.entries) {
            val back = LedgerMapper.toDomain(LedgerMapper.toEntity(domainEntry.copy(interactionModel = im)))
            assertEquals(im, back.interactionModel)
        }
    }

    @Test
    fun `every Tier value round-trips`() {
        for (t in Tier.entries) {
            val back = LedgerMapper.toDomain(LedgerMapper.toEntity(domainEntry.copy(tier = t)))
            assertEquals(t, back.tier)
        }
    }

    @Test
    fun `every Status value round-trips`() {
        for (s in Status.entries) {
            val back = LedgerMapper.toDomain(LedgerMapper.toEntity(domainEntry.copy(status = s)))
            assertEquals(s, back.status)
        }
    }

    @Test
    fun `estimated flag round-trips both polarities`() {
        assertTrue(LedgerMapper.toDomain(LedgerMapper.toEntity(domainEntry.copy(estimated = true))).estimated)
        assertFalse(LedgerMapper.toDomain(LedgerMapper.toEntity(domainEntry.copy(estimated = false))).estimated)
    }

    @Test
    fun `toEntity leaves id defaulted for Room to assign`() {
        assertEquals(0L, LedgerMapper.toEntity(domainEntry).id)
    }
}
