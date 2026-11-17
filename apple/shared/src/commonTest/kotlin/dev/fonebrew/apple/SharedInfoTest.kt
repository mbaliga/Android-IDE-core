// SPDX-License-Identifier: Apache-2.0
package dev.fonebrew.apple

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedInfoTest {
    @Test fun demoTreeRunsTheSharedDomain() {
        val lines = SharedInfo.demoTreeLines()
        assertTrue(lines.any { it == "user: Hello" }, lines.toString())
        assertTrue(lines.any { it == "assistant: Hello there." }, lines.toString())
        assertEquals("branches under n2: 2", lines.last())
    }

    @Test fun diffSummaryIsStable() {
        assertEquals(SharedInfo.diffSummary("a\nb\n", "a\nc\n"), SharedInfo.diffSummary("a\nb\n", "a\nc\n"))
    }

    @Test fun inMemorySecretStoreRoundTripsAndAdmitsItIsNotAKeystore() {
        val s = InMemorySecretStore()
        assertTrue(s.guarantee.contains("Not a keystore"))
        s.put("openai", "sk-test")
        assertEquals("sk-test", s.get("openai"))
        s.delete("openai")
        assertNull(s.get("openai"))
    }
}
