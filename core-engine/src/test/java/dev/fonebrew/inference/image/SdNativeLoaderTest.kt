package dev.fonebrew.inference.image

import dev.fonebrew.inference.CpuFeatureCheck
import dev.fonebrew.inference.UnsupportedCpuException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdNativeLoaderTest {

    private fun core(flags: String) = "processor : 0\nFeatures : fp asimd $flags\n"

    private val full = "asimddp i8mm asimdhp fphp"

    @Test fun `a capable cpu loads the library`() {
        var loaded = 0
        val failure = SdNativeLoader.attempt(
            cpu = { CpuFeatureCheck.evaluate(core(full)) },
            loadLibrary = { loaded++ },
        )
        assertNull(failure)
        assertEquals(1, loaded)
    }

    @Test fun `a missing feature is refused before loadLibrary is ever called`() {
        var loaded = 0
        val failure = SdNativeLoader.attempt(
            cpu = { CpuFeatureCheck.evaluate(core("asimddp asimdhp fphp")) }, // no i8mm
            loadLibrary = { loaded++ },
        )
        assertTrue(failure is UnsupportedCpuException)
        assertEquals(0, loaded)
        assertTrue(failure!!.message!!.contains("i8mm"))
        assertTrue(failure.message!!.contains("image"))
    }

    @Test fun `big little is judged by the weakest core`() {
        var loaded = 0
        val failure = SdNativeLoader.attempt(
            cpu = { CpuFeatureCheck.evaluate(core(full) + core("asimdhp fphp")) },
            loadLibrary = { loaded++ },
        )
        assertTrue(failure is UnsupportedCpuException)
        assertTrue(failure!!.message!!.contains("dotprod"))
        assertEquals(0, loaded)
    }

    @Test fun `unreadable cpuinfo does not block and still tries to load`() {
        var loaded = 0
        val failure = SdNativeLoader.attempt(
            cpu = { CpuFeatureCheck.ofThisDevice { null } },
            loadLibrary = { loaded++ },
        )
        assertNull(failure)
        assertEquals(1, loaded)
    }

    @Test fun `a real load failure is returned, not thrown`() {
        val boom = UnsatisfiedLinkError("no libaarso_sd")
        val failure = SdNativeLoader.attempt(
            cpu = { CpuFeatureCheck.evaluate(core(full)) },
            loadLibrary = { throw boom },
        )
        assertEquals(boom, failure)
    }
}
