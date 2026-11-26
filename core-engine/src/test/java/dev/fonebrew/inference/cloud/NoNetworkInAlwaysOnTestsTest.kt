package dev.fonebrew.inference.cloud

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard: the always-on cloud tests must only ever name loopback / example hosts. The opt-in
 * [LiveContractTest] is exempt (it takes its URL from [dev.fonebrew.domain.cloud.ProviderKind]
 * and is gated by explicit environment opt-in). Reads this module's test sources, so a pasted
 * real provider URL in a fixture test fails the gate instead of silently dialling out.
 */
class NoNetworkInAlwaysOnTestsTest {
    @Test fun always_on_cloud_tests_name_only_loopback_or_example_hosts() {
        val dir = File("src/test/java/dev/fonebrew/inference/cloud")
        assertTrue("expected test source dir at ${dir.absolutePath}", dir.isDirectory)
        val resources = File("src/test/resources/cloud-contract")
        val url = Regex("""https?://([A-Za-z0-9.\-]+)""")
        val allowed = Regex("""127\.0\.0\.1|localhost|(.+\.)?example(\.(com|org))?""")
        val offenders = (dir.walk() + resources.walk())
            .filter { it.isFile && it.name != "LiveContractTest.kt" && it.name != "NoNetworkInAlwaysOnTestsTest.kt" }
            .flatMap { f -> url.findAll(f.readText()).map { f.name to it.groupValues[1] } }
            .filterNot { (_, host) -> allowed.matches(host) }
            .toList()
        assertTrue("non-loopback hosts in always-on tests: $offenders", offenders.isEmpty())
    }
}
