package dev.aarso.domain.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTest {

    @Test fun `every guide has a non-blank title and at least one step`() {
        for (g in Guides.all) {
            assertTrue("guide ${g.id} title", g.title.isNotBlank())
            assertTrue("guide ${g.id} steps", g.steps.isNotEmpty())
            for (s in g.steps) {
                assertTrue("step title in ${g.id}", s.title.isNotBlank())
                assertTrue("step body in ${g.id}", s.body.isNotBlank())
            }
        }
    }

    @Test fun `guide ids are unique`() {
        val ids = Guides.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `byId resolves known guides and rejects unknown`() {
        assertNotNull(Guides.byId("connect_git"))
        assertEquals("Connect to a machine over SSH", Guides.byId("connect_ssh")?.title)
        assertNull(Guides.byId("nope"))
    }
}
