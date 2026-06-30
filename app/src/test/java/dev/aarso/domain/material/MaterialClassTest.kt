package dev.aarso.domain.material

import dev.aarso.domain.model.ModelSpec
import dev.aarso.domain.model.Runtime
import dev.aarso.domain.template.TemplateId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialClassTest {

    private fun spec(id: String, runtime: Runtime) = ModelSpec(
        id = id,
        displayName = id,
        family = "test",
        contextWindow = 4096,
        tokenizerId = "test:$id",
        templateId = TemplateId.PLAIN,
        runtime = runtime,
        watched = runtime == Runtime.CLOUD,
    )

    @Test fun `provenance maps to the optical class`() {
        assertEquals(MaterialClass.REFLECTIVE, Material.classOf(Provenance.LOCAL))
        assertEquals(MaterialClass.RADIANT, Material.classOf(Provenance.ELSEWHERE))
    }

    @Test fun `on-device models are reflective`() {
        assertEquals(MaterialClass.REFLECTIVE, Material.classOf(spec("local:a.gguf", Runtime.LOCAL_GGUF)))
        assertEquals(MaterialClass.REFLECTIVE, Material.classOf(spec("echo", Runtime.ECHO_DEV)))
        assertFalse(Material.isRadiant(spec("local:a.gguf", Runtime.LOCAL_GGUF)))
    }

    @Test fun `a watched cloud model is radiant`() {
        val cloud = spec("cloud:p1", Runtime.CLOUD)
        assertEquals(Provenance.ELSEWHERE, Material.provenanceOf(cloud))
        assertEquals(MaterialClass.RADIANT, Material.classOf(cloud))
        assertTrue(Material.isRadiant(cloud))
    }

    @Test fun `radiant coincides with watched, by binding rule`() {
        // CLAUDE.md #2: cloud == watched == from-elsewhere == radiant. Lock it.
        for (rt in Runtime.values()) {
            val s = spec("m", rt)
            assertEquals("radiant must equal watched for $rt", s.watched, Material.isRadiant(s))
        }
    }
}
