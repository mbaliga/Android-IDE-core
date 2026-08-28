package dev.fonebrew.domain.loop

import dev.fonebrew.domain.council.PatternLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopCatalogSeederTest {

    @Test fun `seeds the catalog exactly once on a fresh install`() {
        val saved = mutableListOf<Loop>()
        var seeded = false

        LoopCatalogSeeder.seedIfNeeded(
            alreadySeeded = false,
            markSeeded = { seeded = true },
            save = { saved += it },
        )

        assertEquals(PatternLibrary.all.size, saved.size)
        assertEquals(LoopCatalog.reference().map { it.id }.toSet(), saved.map { it.id }.toSet())
        assertTrue(seeded)
    }

    @Test fun `never re-runs once the flag is already set`() {
        val saved = mutableListOf<Loop>()
        var markSeededCalls = 0

        LoopCatalogSeeder.seedIfNeeded(
            alreadySeeded = true,
            markSeeded = { markSeededCalls++ },
            save = { saved += it },
        )

        assertTrue(saved.isEmpty())
        assertEquals(0, markSeededCalls)
    }

    @Test fun `upgrade path - seeding beside pre-existing user loops adds, never clobbers or duplicates`() {
        // The realistic first run of this feature: a device upgraded from a build without the
        // seeder, already holding user-created loops (UUID ids), flag not yet set. Simulate
        // LoopStore's id-keyed save (save = replace-by-id) over that pre-populated store.
        val userLoop = LoopCatalog.reference().first()
            .copy(id = "3f2c9a1e-user-made", name = "My own loop")
        val store = linkedMapOf(userLoop.id to userLoop)
        var seeded = false

        LoopCatalogSeeder.seedIfNeeded(
            alreadySeeded = false,
            markSeeded = { seeded = true },
            save = { store[it.id] = it },
        )

        assertEquals(userLoop, store[userLoop.id]) // untouched, byte-for-byte
        assertEquals(1 + PatternLibrary.all.size, store.size) // added beside, no dup/clobber
        assertTrue(LoopCatalog.reference().map { it.id }.all { it in store })
        assertTrue(seeded)
    }

    @Test fun `an already-seeded flag never overwrites a user's edited copy`() {
        // A user could have edited (or deleted) a loop sharing a catalog id -- once the flag
        // is set, save() must never be invoked again to clobber it.
        var saveInvoked = false
        LoopCatalogSeeder.seedIfNeeded(
            alreadySeeded = true,
            markSeeded = {},
            save = { saveInvoked = true },
        )
        assertFalse(saveInvoked)
    }
}
