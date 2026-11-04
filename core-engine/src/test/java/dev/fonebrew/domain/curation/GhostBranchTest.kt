package dev.fonebrew.domain.curation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GhostBranchTest {

    @Test fun `rewinding past an existing future ghosts the old leaf`() {
        val ghost = Rewind.planGhost(
            currentLeafId = "leaf",
            rewindToId = "earlier",
            isDescendant = true,
            now = 100L,
        )
        assertEquals(GhostBranch("leaf", 100L, GhostReason.REWIND), ghost)
    }

    @Test fun `rewinding to a node with no existing future ghosts nothing`() {
        val ghost = Rewind.planGhost(
            currentLeafId = "leaf",
            rewindToId = "leaf's-sibling-fork-point",
            isDescendant = false,
            now = 100L,
        )
        assertNull(ghost)
    }

    @Test fun `rewinding to the current leaf itself is a no-op`() {
        val ghost = Rewind.planGhost(
            currentLeafId = "leaf",
            rewindToId = "leaf",
            isDescendant = true, // a node trivially "descends" from itself in some tree walks
            now = 100L,
        )
        assertNull(ghost)
    }

    @Test fun `a roundtable loser is ghosted with its own reason`() {
        val ghost = Rewind.planGhost(
            currentLeafId = "loser-tip",
            rewindToId = "fork",
            isDescendant = true,
            now = 50L,
            reason = GhostReason.ROUNDTABLE_LOSER,
        )
        assertEquals(GhostReason.ROUNDTABLE_LOSER, ghost?.reason)
    }
}
