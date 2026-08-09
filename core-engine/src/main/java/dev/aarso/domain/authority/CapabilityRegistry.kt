package dev.aarso.domain.authority

import dev.aarso.contracts.authority.AuthorityRung

/**
 * A compile-time mirror of `schemas/loops/registries/capability-ids.v1.json` (frozen at
 * WP-1L-G0) — the `id -> authorityRung` (+ `requiresPurposeBinding`) lookup
 * [AuthorityEngine] needs to resolve what rung a requested `fb.*` capability actually sits at
 * (CAPABILITY_AUTHORITY_MODEL.md §2: "the per-capability mapping lives in the external
 * registry, not this general model" -- this object IS that external registry, made
 * queryable from Kotlin rather than re-parsed from JSON on every lookup).
 *
 * Deliberately hardcoded, not loaded from the JSON file at runtime: that file lives at the repo
 * root (`schemas/loops/registries/`), outside any module's asset/resource directory, so a real
 * runtime load would need new build wiring this work package does not otherwise need. Kept in
 * sync by hand -- every entry here MUST match its counterpart in capability-ids.v1.json exactly;
 * [CapabilityRegistryConsistencyTest] enforces this by loading and diffing against the real file.
 */
object CapabilityRegistry {

    data class Entry(val authorityRung: AuthorityRung, val requiresPurposeBinding: Boolean)

    private val ENTRIES: Map<String, Entry> = mapOf(
        "fb.workspace.observe" to Entry(AuthorityRung.OBSERVE, false),
        "fb.repo.read" to Entry(AuthorityRung.READ, false),
        "fb.repo.propose_change" to Entry(AuthorityRung.PROPOSE, false),
        "fb.repo.write_draft" to Entry(AuthorityRung.MODIFY_DRAFT, false),
        "fb.repo.commit" to Entry(AuthorityRung.EXECUTE_REVERSIBLE, false),
        "fb.repo.push_remote" to Entry(AuthorityRung.EXECUTE_EXTERNAL, true),
        "fb.repo.history_rewrite" to Entry(AuthorityRung.EXECUTE_DESTRUCTIVE, true),
        "fb.exec.local_process" to Entry(AuthorityRung.EXECUTE_REVERSIBLE, false),
        "fb.exec.ssh_remote" to Entry(AuthorityRung.EXECUTE_EXTERNAL, true),
        "fb.ci.dispatch" to Entry(AuthorityRung.EXECUTE_EXTERNAL, true),
        "fb.device.serial_read" to Entry(AuthorityRung.EXECUTE_REVERSIBLE, true),
        "fb.device.flash" to Entry(AuthorityRung.EXECUTE_DESTRUCTIVE, true),
        "fb.network.egress" to Entry(AuthorityRung.EXECUTE_EXTERNAL, true),
        "fb.secret.use" to Entry(AuthorityRung.EXECUTE_EXTERNAL, true),
        "fb.model.local_inference" to Entry(AuthorityRung.EXECUTE_REVERSIBLE, false),
        "fb.model.cloud_inference" to Entry(AuthorityRung.EXECUTE_EXTERNAL, true),
        "fb.device.usb_permission" to Entry(AuthorityRung.PROPOSE, true),
        "fb.marketplace.import" to Entry(AuthorityRung.READ, false),
        "fb.marketplace.publish" to Entry(AuthorityRung.PUBLISH_OR_RELEASE, true),
        "fb.release.publish" to Entry(AuthorityRung.PUBLISH_OR_RELEASE, true),
    )

    /** Every known `fb.*` id and its registry entry -- exposed read-only for the consistency test. */
    val all: Map<String, Entry> get() = ENTRIES

    fun entryFor(capabilityId: String): Entry? = ENTRIES[capabilityId]

    fun rungFor(capabilityId: String): AuthorityRung? = ENTRIES[capabilityId]?.authorityRung

    fun requiresPurposeBinding(capabilityId: String): Boolean? = ENTRIES[capabilityId]?.requiresPurposeBinding
}
