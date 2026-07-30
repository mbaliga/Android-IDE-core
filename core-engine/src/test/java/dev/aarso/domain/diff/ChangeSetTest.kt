package dev.aarso.domain.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSetTest {

    @Test fun `op is inferred from old and new text`() {
        assertEquals(ChangeOp.CREATE, FileChange("a", "", "hi").op)
        assertEquals(ChangeOp.DELETE, FileChange("a", "hi", "").op)
        assertEquals(ChangeOp.MODIFY, FileChange("a", "hi", "ho").op)
    }

    @Test fun `a no-op change is excluded from effective and stat`() {
        val cs = ChangeSet(listOf(FileChange("a", "x", "x"), FileChange("b", "1\n2", "1\n2\n3")))
        assertEquals(1, cs.effective.size)
        assertFalse(cs.isEmpty)
        assertEquals(1, cs.stat.added)
        assertEquals(0, cs.stat.removed)
    }

    @Test fun `aggregate stat sums across files`() {
        val cs = ChangeSet(
            listOf(
                FileChange("a", "old", "new"),       // 1 add, 1 remove
                FileChange("b", "", "x\ny"),         // 2 adds (create)
            ),
        )
        assertEquals(3, cs.stat.added)
        assertEquals(1, cs.stat.removed)
    }

    @Test fun `unified diff carries git-style paths`() {
        val cs = ChangeSet(listOf(FileChange("src/Main.kt", "a", "b")))
        val diff = cs.unifiedDiff()
        assertTrue(diff.contains("--- a/src/Main.kt"))
        assertTrue(diff.contains("+++ b/src/Main.kt"))
    }

    @Test fun `of() builds creates, modifies, and deletes from two maps`() {
        val old = mapOf("keep.kt" to "v1", "gone.kt" to "bye")
        val new = mapOf("keep.kt" to "v2", "added.kt" to "hi")
        val cs = ChangeSet.of(old, new)
        val byPath = cs.changes.associateBy { it.path }
        assertEquals(ChangeOp.MODIFY, byPath.getValue("keep.kt").op)
        assertEquals(ChangeOp.DELETE, byPath.getValue("gone.kt").op)
        assertEquals(ChangeOp.CREATE, byPath.getValue("added.kt").op)
    }

    @Test fun `an all-noop set is empty`() {
        assertTrue(ChangeSet(listOf(FileChange("a", "x", "x"))).isEmpty)
    }
}
