// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.aarso.domain.contracts

import dev.aarso.contracts.authority.AuthorityDecision
import dev.aarso.contracts.authority.AuthorityRung
import dev.aarso.contracts.authority.ConfirmationMode
import dev.aarso.contracts.authority.ConfirmationPolicy
import dev.aarso.contracts.authority.DelegationRule
import dev.aarso.contracts.authority.Grant
import dev.aarso.contracts.authority.GrantConstraints
import dev.aarso.contracts.authority.Principal
import dev.aarso.contracts.authority.PrincipalKind
import dev.aarso.contracts.authority.PrincipalStatus
import dev.aarso.contracts.authority.RedactionOrSandboxDetail
import dev.aarso.contracts.authority.RedactionOrSandboxMode
import dev.aarso.contracts.authority.ResourceKind
import dev.aarso.contracts.authority.ResourceScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Encode/decode for `dev.aarso.contracts.authority` (WP-4), same unknown-field-preserving pattern as [EnvelopeCodec]/[WorkspaceCodec]. */
object AuthorityCodec {

    private val PRINCIPAL_KNOWN_KEYS = setOf(
        "principalId", "kind", "displayName", "parentPrincipalId", "status", "createdAtUtc", "rootUserPrincipalId"
    )

    fun encodePrincipal(p: Principal): JSONObject {
        val obj = JSONObject()
        obj.put("principalId", p.principalId)
        obj.put("kind", p.kind.name)
        obj.put("displayName", p.displayName)
        obj.put("parentPrincipalId", p.parentPrincipalId)
        obj.put("status", p.status.name)
        obj.put("createdAtUtc", p.createdAtUtc.toString())
        obj.put("rootUserPrincipalId", p.rootUserPrincipalId)
        mergeUnknownFields(obj, p.unknownFields)
        return obj
    }

    fun decodePrincipal(json: JSONObject): Principal = Principal(
        principalId = json.getString("principalId"),
        kind = PrincipalKind.valueOf(json.getString("kind")),
        displayName = json.getString("displayName"),
        parentPrincipalId = json.optStringOrNull("parentPrincipalId"),
        status = PrincipalStatus.valueOf(json.getString("status")),
        createdAtUtc = Instant.parse(json.getString("createdAtUtc")),
        rootUserPrincipalId = json.optStringOrNull("rootUserPrincipalId"),
        unknownFields = extractUnknownFields(json, PRINCIPAL_KNOWN_KEYS)
    )

    fun encodeResourceScope(r: ResourceScope): JSONObject = JSONObject().apply {
        put("kind", r.kind.name)
        put("locator", r.locator)
    }

    fun decodeResourceScope(json: JSONObject): ResourceScope =
        ResourceScope(kind = ResourceKind.valueOf(json.getString("kind")), locator = json.getString("locator"))

    private val GRANT_KNOWN_KEYS = setOf(
        "grantId", "principalId", "capabilityIds", "authorityRung", "resourceScope", "constraints",
        "expiresAtUtc", "confirmationPolicy", "delegationRule"
    )

    fun encodeGrant(g: Grant): JSONObject {
        val obj = JSONObject()
        obj.put("grantId", g.grantId)
        obj.put("principalId", g.principalId)
        obj.put("capabilityIds", JSONArray(g.capabilityIds))
        obj.put("authorityRung", g.authorityRung.name)
        obj.put("resourceScope", encodeResourceScope(g.resourceScope))
        obj.put("constraints", JSONObject().apply {
            put("purpose", g.constraints.purpose)
            put("additionalConstraints", kotlinValueToJson(g.constraints.additionalConstraints))
        })
        obj.put("expiresAtUtc", g.expiresAtUtc.toString())
        obj.put("confirmationPolicy", JSONObject().apply {
            put("mode", g.confirmationPolicy.mode.name)
            put("aboveRung", g.confirmationPolicy.aboveRung?.name)
        })
        obj.put("delegationRule", JSONObject().apply {
            put("delegable", g.delegationRule.delegable)
            put("maxDelegatedRung", g.delegationRule.maxDelegatedRung?.name)
            put("transitiveDelegationAllowed", g.delegationRule.transitiveDelegationAllowed)
            put("requiresFreshUserApprovalForWidening", g.delegationRule.requiresFreshUserApprovalForWidening)
        })
        mergeUnknownFields(obj, g.unknownFields)
        return obj
    }

    fun decodeGrant(json: JSONObject): Grant {
        val capabilityIdsJson = json.getJSONArray("capabilityIds")
        val constraintsJson = json.getJSONObject("constraints")
        val confirmationJson = json.getJSONObject("confirmationPolicy")
        val delegationJson = json.getJSONObject("delegationRule")
        @Suppress("UNCHECKED_CAST")
        val additionalConstraints = (jsonValueToKotlin(constraintsJson.optJSONObjectOrNull("additionalConstraints") ?: JSONObject()) as Map<String, Any?>)
        return Grant(
            grantId = json.getString("grantId"),
            principalId = json.getString("principalId"),
            capabilityIds = (0 until capabilityIdsJson.length()).map { capabilityIdsJson.getString(it) },
            authorityRung = AuthorityRung.valueOf(json.getString("authorityRung")),
            resourceScope = decodeResourceScope(json.getJSONObject("resourceScope")),
            constraints = GrantConstraints(
                purpose = constraintsJson.optStringOrNull("purpose"),
                additionalConstraints = additionalConstraints
            ),
            expiresAtUtc = Instant.parse(json.getString("expiresAtUtc")),
            confirmationPolicy = ConfirmationPolicy(
                mode = ConfirmationMode.valueOf(confirmationJson.getString("mode")),
                aboveRung = confirmationJson.optStringOrNull("aboveRung")?.let { AuthorityRung.valueOf(it) }
            ),
            delegationRule = DelegationRule(
                delegable = delegationJson.getBoolean("delegable"),
                maxDelegatedRung = delegationJson.optStringOrNull("maxDelegatedRung")?.let { AuthorityRung.valueOf(it) },
                transitiveDelegationAllowed = delegationJson.getBoolean("transitiveDelegationAllowed"),
                requiresFreshUserApprovalForWidening = delegationJson.getBoolean("requiresFreshUserApprovalForWidening")
            ),
            unknownFields = extractUnknownFields(json, GRANT_KNOWN_KEYS)
        )
    }

    private val DECISION_COMMON_KEYS = setOf(
        "outcome", "decisionId", "requestObjectId", "requestingPrincipalId", "requestedCapabilityId",
        "requestedResourceScope", "reasonCode", "policyVersion", "decidedAtUtc", "evidenceLinks",
        "matchedGrantId", "confirmationPromptRef", "requiredRung", "redactionOrSandbox"
    )

    fun encodeAuthorityDecision(d: AuthorityDecision): JSONObject {
        val obj = JSONObject()
        val outcome = when (d) {
            is AuthorityDecision.Allow -> "ALLOW"
            is AuthorityDecision.Deny -> "DENY"
            is AuthorityDecision.RequireConfirmation -> "REQUIRE_CONFIRMATION"
            is AuthorityDecision.RequireStrongerAuthority -> "REQUIRE_STRONGER_AUTHORITY"
            is AuthorityDecision.AllowWithRedactionOrSandbox -> "ALLOW_WITH_REDACTION_OR_SANDBOX"
        }
        obj.put("outcome", outcome)
        obj.put("decisionId", d.decisionId)
        obj.put("requestObjectId", d.requestObjectId)
        obj.put("requestingPrincipalId", d.requestingPrincipalId)
        obj.put("requestedCapabilityId", d.requestedCapabilityId)
        obj.put("requestedResourceScope", encodeResourceScope(d.requestedResourceScope))
        obj.put("reasonCode", d.reasonCode)
        obj.put("policyVersion", d.policyVersion)
        obj.put("decidedAtUtc", d.decidedAtUtc.toString())
        obj.put("evidenceLinks", JSONArray(d.evidenceLinks))
        when (d) {
            is AuthorityDecision.Allow -> obj.put("matchedGrantId", d.matchedGrantId)
            is AuthorityDecision.Deny -> obj.put("matchedGrantId", d.matchedGrantId)
            is AuthorityDecision.RequireConfirmation -> {
                obj.put("matchedGrantId", d.matchedGrantId)
                obj.put("confirmationPromptRef", d.confirmationPromptRef)
            }
            is AuthorityDecision.RequireStrongerAuthority -> {
                obj.put("matchedGrantId", d.matchedGrantId)
                obj.put("requiredRung", d.requiredRung.name)
            }
            is AuthorityDecision.AllowWithRedactionOrSandbox -> {
                obj.put("matchedGrantId", d.matchedGrantId)
                obj.put("redactionOrSandbox", JSONObject().apply {
                    put("mode", d.redactionOrSandbox.mode.name)
                    put("detail", d.redactionOrSandbox.detail)
                })
            }
        }
        return obj
    }

    fun decodeAuthorityDecision(json: JSONObject): AuthorityDecision {
        val decisionId = json.getString("decisionId")
        val requestObjectId = json.getString("requestObjectId")
        val requestingPrincipalId = json.getString("requestingPrincipalId")
        val requestedCapabilityId = json.getString("requestedCapabilityId")
        val requestedResourceScope = decodeResourceScope(json.getJSONObject("requestedResourceScope"))
        val reasonCode = json.getString("reasonCode")
        val policyVersion = json.getString("policyVersion")
        val decidedAtUtc = Instant.parse(json.getString("decidedAtUtc"))
        val evidenceLinksJson = json.getJSONArray("evidenceLinks")
        val evidenceLinks = (0 until evidenceLinksJson.length()).map { evidenceLinksJson.getString(it) }

        return when (json.getString("outcome")) {
            "ALLOW" -> AuthorityDecision.Allow(
                decisionId, requestObjectId, requestingPrincipalId, requestedCapabilityId, requestedResourceScope,
                reasonCode, policyVersion, decidedAtUtc, json.getString("matchedGrantId"), evidenceLinks
            )
            "DENY" -> AuthorityDecision.Deny(
                decisionId, requestObjectId, requestingPrincipalId, requestedCapabilityId, requestedResourceScope,
                reasonCode, policyVersion, decidedAtUtc, json.optStringOrNull("matchedGrantId"), evidenceLinks
            )
            "REQUIRE_CONFIRMATION" -> AuthorityDecision.RequireConfirmation(
                decisionId, requestObjectId, requestingPrincipalId, requestedCapabilityId, requestedResourceScope,
                reasonCode, policyVersion, decidedAtUtc, json.getString("matchedGrantId"), json.getString("confirmationPromptRef"), evidenceLinks
            )
            "REQUIRE_STRONGER_AUTHORITY" -> AuthorityDecision.RequireStrongerAuthority(
                decisionId, requestObjectId, requestingPrincipalId, requestedCapabilityId, requestedResourceScope,
                reasonCode, policyVersion, decidedAtUtc, AuthorityRung.valueOf(json.getString("requiredRung")),
                json.optStringOrNull("matchedGrantId"), evidenceLinks
            )
            "ALLOW_WITH_REDACTION_OR_SANDBOX" -> {
                val rs = json.getJSONObject("redactionOrSandbox")
                AuthorityDecision.AllowWithRedactionOrSandbox(
                    decisionId, requestObjectId, requestingPrincipalId, requestedCapabilityId, requestedResourceScope,
                    reasonCode, policyVersion, decidedAtUtc, json.getString("matchedGrantId"),
                    RedactionOrSandboxDetail(RedactionOrSandboxMode.valueOf(rs.getString("mode")), rs.getString("detail")),
                    evidenceLinks
                )
            }
            else -> throw IllegalArgumentException("Unknown AuthorityDecision outcome: ${json.getString("outcome")}")
        }
    }
}
