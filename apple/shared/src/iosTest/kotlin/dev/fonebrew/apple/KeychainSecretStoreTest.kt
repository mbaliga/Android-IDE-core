// SPDX-License-Identifier: Apache-2.0
package dev.fonebrew.apple

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Runs only on the iOS simulator (macOS CI lane). CODE-ONLY until that lane is seen green. */
class KeychainSecretStoreTest {
    @Test fun roundTrip() {
        val s = KeychainSecretStore(service = "com.asoc.fonebrew.test")
        s.put("acct", "v1"); assertEquals("v1", s.get("acct"))
        s.put("acct", "v2"); assertEquals("v2", s.get("acct"))
        s.delete("acct"); assertNull(s.get("acct"))
    }
}
