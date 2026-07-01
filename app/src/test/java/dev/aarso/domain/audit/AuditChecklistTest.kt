package dev.aarso.domain.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditChecklistTest {

    @Test fun `default is non-empty and every id is unique`() {
        val checks = AuditChecklist.default()
        assertTrue("default checklist should not be empty", checks.isNotEmpty())
        val ids = checks.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
    }

    @Test fun `default items all start pending`() {
        assertTrue(AuditChecklist.default().all { it.status == AuditStatus.PENDING })
    }

    @Test fun `promptFor is never blank for any default check`() {
        for (check in AuditChecklist.default()) {
            val prompt = AuditChecklist.promptFor(check)
            assertTrue("prompt for ${check.id} should not be blank", prompt.isNotBlank())
        }
    }

    @Test fun `promptFor covers every category, non-blank`() {
        for (category in AuditCategory.values()) {
            val probe = AuditCheck("probe", "probe", "probe", category)
            assertTrue("prompt for $category should not be blank", AuditChecklist.promptFor(probe).isNotBlank())
        }
    }

    @Test fun `summary counts each status correctly on a mixed list`() {
        val checks = listOf(
            AuditCheck("a", "a", "a", AuditCategory.TESTS, AuditStatus.PASSED),
            AuditCheck("b", "b", "b", AuditCategory.LINT, AuditStatus.PASSED),
            AuditCheck("c", "c", "c", AuditCategory.BUILD, AuditStatus.FAILED),
            AuditCheck("d", "d", "d", AuditCategory.OFFLINE, AuditStatus.PENDING),
            AuditCheck("e", "e", "e", AuditCategory.SECURITY, AuditStatus.RUNNING),
            AuditCheck("f", "f", "f", AuditCategory.I18N, AuditStatus.SKIPPED),
        )
        val counts = AuditChecklist.summary(checks)
        assertEquals(6, counts.total)
        assertEquals(2, counts.passed)
        assertEquals(1, counts.failed)
        assertEquals(1, counts.pending)
        assertEquals(1, counts.running)
        assertEquals(1, counts.skipped)
    }

    @Test fun `summary of empty list is all zero`() {
        val counts = AuditChecklist.summary(emptyList())
        assertEquals(AuditChecklist.Counts(0, 0, 0, 0, 0, 0), counts)
    }

    @Test fun `withStatus updates only the target and leaves the rest unchanged`() {
        val original = AuditChecklist.default()
        val targetId = original[3].id
        val updated = AuditChecklist.withStatus(original, targetId, AuditStatus.PASSED)

        // The target moved to PASSED.
        assertEquals(AuditStatus.PASSED, updated.first { it.id == targetId }.status)

        // Same length, same order of ids.
        assertEquals(original.map { it.id }, updated.map { it.id })

        // Every non-target item is identical to the original.
        for (i in original.indices) {
            if (original[i].id == targetId) continue
            assertEquals(original[i], updated[i])
        }

        // Immutability: the input list is untouched.
        assertTrue(original.all { it.status == AuditStatus.PENDING })
        assertNotEquals(original[3], updated[3])
    }

    @Test fun `withStatus on an unknown id is a no-op`() {
        val original = AuditChecklist.default()
        val updated = AuditChecklist.withStatus(original, "no-such-id", AuditStatus.FAILED)
        assertEquals(original, updated)
    }
}
