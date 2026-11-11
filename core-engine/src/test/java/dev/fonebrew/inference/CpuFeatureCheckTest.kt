package dev.fonebrew.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuFeatureCheckTest {

    private fun cpuinfo(vararg featureLines: String) = featureLines.mapIndexed { i, f ->
        "processor\t: $i\nBogoMIPS\t: 38.40\nFeatures\t: $f\nCPU implementer\t: 0x41\n"
    }.joinToString("\n")

    private val full = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp i8mm bf16"
    private val noI8mm = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
    private val noDotprod = "fp asimd fphp asimdhp i8mm"
    private val noFp16 = "fp asimd asimddp i8mm"

    @Test fun `a CPU with dotprod i8mm and fp16 is supported`() {
        assertEquals(CpuSupport.Supported, CpuFeatureCheck.evaluate(cpuinfo(full, full)))
        assertNull(CpuFeatureCheck.evaluate(cpuinfo(full)).blockReason)
    }

    @Test fun `a CPU without i8mm is blocked and names the missing feature`() {
        val r = CpuFeatureCheck.evaluate(cpuinfo(noI8mm, noI8mm))
        assertEquals(CpuSupport.Missing(listOf("i8mm")), r)
        assertTrue(r.blockReason!!.contains("i8mm"))
    }

    @Test fun `each required feature is checked independently`() {
        assertEquals(CpuSupport.Missing(listOf("dotprod")), CpuFeatureCheck.evaluate(cpuinfo(noDotprod)))
        assertEquals(CpuSupport.Missing(listOf("fp16")), CpuFeatureCheck.evaluate(cpuinfo(noFp16)))
        assertEquals(
            CpuSupport.Missing(listOf("dotprod", "i8mm", "fp16")),
            CpuFeatureCheck.evaluate(cpuinfo("fp asimd")),
        )
    }

    @Test fun `fp16 needs both the vector and scalar half-precision flags`() {
        assertEquals(
            CpuSupport.Missing(listOf("fp16")),
            CpuFeatureCheck.evaluate(cpuinfo("asimdhp asimddp i8mm")),
        )
    }

    @Test fun `big little cores that differ are judged by the weakest core`() {
        assertEquals(CpuSupport.Missing(listOf("i8mm")), CpuFeatureCheck.evaluate(cpuinfo(full, noI8mm)))
    }

    @Test fun `flags match whole words not substrings`() {
        // "i8mm2" must not satisfy "i8mm".
        assertEquals(
            CpuSupport.Missing(listOf("i8mm")),
            CpuFeatureCheck.evaluate(cpuinfo("asimddp asimdhp fphp i8mm2")),
        )
    }

    @Test fun `unreadable or featureless cpuinfo is unknown and does not block`() {
        assertTrue(CpuFeatureCheck.evaluate(null) is CpuSupport.Unknown)
        assertTrue(CpuFeatureCheck.evaluate("model name\t: Intel\nflags\t: sse4_2 avx2\n") is CpuSupport.Unknown)
        assertNull(CpuFeatureCheck.evaluate(null).blockReason)
        CpuFeatureCheck.evaluate(null).requireSupported() // must not throw
    }

    @Test fun `a throwing reader is unknown not a crash`() {
        assertTrue(CpuFeatureCheck.ofThisDevice { error("denied") } is CpuSupport.Unknown)
    }

    @Test fun `requireSupported throws a legible exception for a missing feature`() {
        val r = CpuFeatureCheck.evaluate(cpuinfo(noI8mm))
        val e = runCatching { r.requireSupported() }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e is UnsupportedCpuException)
        assertTrue(e!!.message!!.contains("i8mm"))
    }
}
