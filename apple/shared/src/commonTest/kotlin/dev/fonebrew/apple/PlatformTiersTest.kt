// SPDX-License-Identifier: Apache-2.0
package dev.fonebrew.apple

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformTiersTest {
    @Test fun everyNonAvailableTierSaysWhy() {
        PlatformTiers.all.filter { it.availability != Availability.Available }
            .forEach { assertTrue(it.reason.length > 10, "${it.id} needs an honest reason") }
    }

    @Test fun androidOnlyTiersAreUnavailableNotFaked() {
        for (id in listOf("overlay", "assist", "usb", "apk_install", "local_process", "image_gen")) {
            assertEquals(Availability.Unavailable, PlatformTiers.byId(id)?.availability, id)
        }
    }

    @Test fun noTierClaimsOnDeviceLlmWorks() {
        assertEquals(Availability.Stub, PlatformTiers.byId("on_device_llm")?.availability)
        assertEquals(Availability.Stub, PlatformTiers.byId("cloud")?.availability)
        assertNull(PlatformTiers.byId("nope"))
    }

    @Test fun idsAreUnique() {
        assertEquals(PlatformTiers.all.size, PlatformTiers.all.map { it.id }.toSet().size)
    }

    @Test fun cloudIsAlwaysWatchedOnDeviceNever() {
        assertTrue(ProviderKind.Cloud.isWatchedObject())
        assertFalse(ProviderKind.OnDevice.isWatchedObject())
    }
}
