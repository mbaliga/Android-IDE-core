package dev.fonebrew.domain.execution

import dev.fonebrew.contracts.execution.AuthorityGrantRef
import dev.fonebrew.contracts.execution.ExecutionBudget
import dev.fonebrew.contracts.execution.ExecutionRequest
import dev.fonebrew.contracts.execution.ExternalDuplicateBehavior
import dev.fonebrew.contracts.execution.FgsType
import dev.fonebrew.contracts.execution.OperationClass
import dev.fonebrew.contracts.execution.RequestEnvironment
import dev.fonebrew.contracts.execution.SessionReauthorization
import dev.fonebrew.contracts.execution.TypedOperation
import dev.fonebrew.domain.contracts.IdGenerator

/**
 * Builds the one [ExecutionRequest] a Run panel invocation needs, satisfying every `init{}`
 * constraint [ExecutionRequest] itself enforces (FB-RAT-EXE-008 idempotency, platform-grounding
 * FGS/openEnded pairing) rather than leaving the UI layer to get those right by hand. Pure --
 * no clock/id default is baked in without a seam a test can override.
 */
object RunRequestFactory {

    /** No FGS wrapper exists for a Run yet (a real bounded-duration foreground service is a
     *  named follow-up, not silently assumed) -- [dev.fonebrew.contracts.execution.FgsType.NONE]
     *  is the honest declaration for that, not a placeholder. */
    private const val SESSION_MAX_SECONDS = 3600L

    fun build(
        target: RunTarget,
        command: String,
        grantId: String,
        workingRevision: String? = null,
        now: () -> java.time.Instant = java.time.Instant::now,
        idGenerator: () -> String = { "req_" + IdGenerator.generate() },
    ): ExecutionRequest {
        // A CI dispatch is always external per FB-RAT-EXE-008 --
        // [CiActionsExecutionProvider.prepare] itself `require`s sideEffectExternal == true, so
        // this must agree with that provider's own contract, not just this factory's opinion.
        val ci = target is RunTarget.Ci
        return ExecutionRequest(
            id = idGenerator(),
            targetId = target.targetId,
            operation = TypedOperation(
                operationClass = if (ci) OperationClass.CI_DISPATCH else OperationClass.ONE_SHOT_COMMAND,
                command = command,
            ),
            workingRevision = workingRevision,
            environment = RequestEnvironment(),
            secretHandles = emptyList(),
            budget = ExecutionBudget(
                sessionReauthorization = SessionReauthorization(maxSessionSeconds = SESSION_MAX_SECONDS, requiresUserReauth = false),
            ),
            authorityGrant = AuthorityGrantRef(grantId = grantId, scopes = listOf(target.capabilityId)),
            expectedOutputs = emptyList(),
            idempotencyKey = if (ci) idGenerator() else null,
            sideEffectExternal = ci,
            fgsType = FgsType.NONE,
            openEnded = false,
            externalDuplicateBehavior = if (ci) ExternalDuplicateBehavior.RETURN_PRIOR_RESULT else null,
        )
    }
}
