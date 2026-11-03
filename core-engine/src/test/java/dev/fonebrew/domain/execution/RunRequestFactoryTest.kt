package dev.fonebrew.domain.execution

import dev.fonebrew.contracts.execution.ExternalDuplicateBehavior
import dev.fonebrew.contracts.execution.FgsType
import dev.fonebrew.contracts.execution.OperationClass
import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind
import dev.fonebrew.domain.remote.RemoteHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RunRequestFactoryTest {

    private val now = { Instant.parse("2026-08-28T00:00:00Z") }
    private var idCounter = 0
    private val ids = { "id_${idCounter++}" }

    @Test
    fun `local -- one-shot, not external, no idempotency key required`() {
        val req = RunRequestFactory.build(RunTarget.Local, "./gradlew test", "grant_1", now = now, idGenerator = ids)
        assertEquals(OperationClass.ONE_SHOT_COMMAND, req.operation.operationClass)
        assertFalse(req.sideEffectExternal)
        assertNull(req.idempotencyKey)
        assertNull(req.externalDuplicateBehavior)
        assertEquals(FgsType.NONE, req.fgsType)
        assertFalse(req.openEnded)
        assertEquals("grant_1", req.authorityGrant.grantId)
        assertEquals(listOf(RunTarget.Local.capabilityId), req.authorityGrant.scopes)
        assertEquals(RunTarget.Local.targetId, req.targetId)
        assertEquals("./gradlew test", req.operation.command)
    }

    @Test
    fun `ssh -- also one-shot and not external (a re-run is not a duplicate-sensitive side effect)`() {
        val target = RunTarget.Ssh(RemoteHost(alias = "homelab", hostname = "10.0.0.5", username = "pi"))
        val req = RunRequestFactory.build(target, "make test", "grant_2", now = now, idGenerator = ids)
        assertEquals(OperationClass.ONE_SHOT_COMMAND, req.operation.operationClass)
        assertFalse(req.sideEffectExternal)
        assertNull(req.idempotencyKey)
    }

    @Test
    fun `ci -- CI_DISPATCH, sideEffectExternal true, carries an idempotency key + duplicate behavior (FB-RAT-EXE-008)`() {
        val target = RunTarget.Ci(
            GitHost(id = "h1", displayName = "d", kind = GitHostKind.GITHUB, baseUrl = "", owner = "me", repo = "r", branch = "main", authorName = "me", authorEmail = "m@e.com"),
        )
        val req = RunRequestFactory.build(target, "ci.yml", "grant_3", now = now, idGenerator = ids)
        assertEquals(OperationClass.CI_DISPATCH, req.operation.operationClass)
        assertTrue(req.sideEffectExternal)
        assertNotNull(req.idempotencyKey)
        assertEquals(ExternalDuplicateBehavior.RETURN_PRIOR_RESULT, req.externalDuplicateBehavior)
        assertEquals("ci.yml", req.operation.command)
    }

    @Test
    fun `every build produces a fresh request id`() {
        val a = RunRequestFactory.build(RunTarget.Local, "x", "g", now = now, idGenerator = ids)
        val b = RunRequestFactory.build(RunTarget.Local, "x", "g", now = now, idGenerator = ids)
        assertTrue(a.id != b.id)
    }
}
