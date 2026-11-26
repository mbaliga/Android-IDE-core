package dev.fonebrew.data.benchmark

import dev.fonebrew.domain.benchmark.Reading
import dev.fonebrew.domain.benchmark.ThermalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbesAndCpuTest {

    @Test fun thermalProbeMapsAndNeverGuesses() {
        assertEquals(Reading.Known(ThermalLevel.MODERATE), Probes.thermal { 2 }.read())
        assertTrue(Probes.thermal { 42 }.read() is Reading.Unknown)
        val boom = Probes.thermal { throw SecurityException("no") }.read()
        assertTrue(boom is Reading.Unknown && boom.why.contains("SecurityException"))
    }

    @Test fun memoryProbeUnknownOnZeroOrThrow() {
        assertEquals(Reading.Known(2048L), Probes.memory { 2048 }.pssKb())
        assertTrue(Probes.memory { 0 }.pssKb() is Reading.Unknown)
        assertTrue(Probes.memory { throw IllegalStateException() }.pssKb() is Reading.Unknown)
    }

    @Test fun batteryProbeUnknownWhenPlatformGivesNothing() {
        assertEquals(Reading.Known(50), Probes.battery { 50 to 100 }.percent())
        assertEquals(Reading.Known(33), Probes.battery { 1 to 3 }.percent())
        assertTrue(Probes.battery { null }.percent() is Reading.Unknown)
        assertTrue(Probes.battery { -1 to 100 }.percent() is Reading.Unknown)
        assertTrue(Probes.battery { 5 to 0 }.percent() is Reading.Unknown)
        assertTrue(Probes.battery { throw RuntimeException() }.percent() is Reading.Unknown)
    }

    @Test fun cpuStringUsesTheExistingCheckAndKeepsUnknownUnknown() {
        val ok = "processor : 0\nFeatures : fp asimd asimddp i8mm asimdhp fphp sve\n"
        val s = CpuFeatureString.describe(ok)
        assertTrue(s.startsWith("required extensions present (dotprod, i8mm, fp16)"))
        assertTrue(s.contains("Features: fp asimd asimddp i8mm asimdhp fphp sve"))
        val missing = CpuFeatureString.describe("Features : fp asimd\n")
        assertTrue(missing.contains("MISSING: dotprod, i8mm, fp16"))
        assertTrue(CpuFeatureString.describe(null).contains("unknown"))
        assertTrue(!CpuFeatureString.describe(null).contains("Features:"))
    }
}
