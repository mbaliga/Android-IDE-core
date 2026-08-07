package dev.aarso.domain.device.broker

import dev.aarso.contracts.devices.BoardRef
import dev.aarso.contracts.devices.CatalogRef

/**
 * `DEVICE_STATE_AND_SAFETY_SPEC.md` §4 step 3 / FB-RAT-DEV-007 ("wrong-board block"), made real.
 * The spec is explicit about the exact matcher a real Device Broker MUST perform: "matching on
 * `(catalogRef, catalogId)` when both sides have a non-null `catalogId`, falling back to
 * `displayName` only when either side is `UNCATALOGED`" -- this object is exactly that function,
 * nothing more. `FirmwareArtifact.boardCompatibility`'s own non-empty `init{}` check
 * (`DeviceContracts.kt`) guarantees the list this matches against is never empty; it does NOT
 * guarantee any entry actually matches the connected device, which is precisely the gap this
 * closes.
 */
object WrongBoardPreflight {

    /** True iff [deviceBoard] matches at least one entry in [artifactCompatibility] by the spec's exact rule. */
    fun isCompatible(deviceBoard: BoardRef, artifactCompatibility: List<BoardRef>): Boolean =
        artifactCompatibility.any { matches(deviceBoard, it) }

    /**
     * Both sides cataloged with a non-null `catalogId`: match on `(catalogRef, catalogId)` only —
     * two boards with the same `catalogId` under *different* catalogs (e.g. an Arduino CLI FQBN
     * that happens to collide textually with an unrelated PlatformIO id) are NOT the same board.
     * Either side `UNCATALOGED` (or missing a `catalogId` despite a cataloged `catalogRef` --
     * treated the same as `UNCATALOGED` for matching purposes, since there is no catalog id to
     * compare): fall back to an exact `displayName` match, the spec's named fallback.
     */
    private fun matches(a: BoardRef, b: BoardRef): Boolean {
        val bothCataloged = a.catalogRef != CatalogRef.UNCATALOGED && b.catalogRef != CatalogRef.UNCATALOGED &&
            a.catalogId != null && b.catalogId != null
        return if (bothCataloged) {
            a.catalogRef == b.catalogRef && a.catalogId == b.catalogId
        } else {
            a.displayName == b.displayName
        }
    }
}
