package dev.aarso.domain.watch

/** One tappable ghost row in the Watch tab's empty state (CORE_PHASES.md P2). Seeds insert a
 *  real, fully-editable [dev.aarso.data.entity.WatchedItemEntity] only on tap — never on
 *  screen load. [amountHint] is a "check current figure" prompt, not an asserted fact: fee
 *  amounts drift and this codebase never states one as current. */
data class WatchSeed(
    val label: String,
    val kind: WatchKind,
    val amountHint: String? = null,
    val note: String = "",
)

object WatchSeeds {
    val TEMPLATES: List<WatchSeed> = listOf(
        WatchSeed(
            label = "Google Play developer registration",
            kind = WatchKind.RENEWAL,
            amountHint = "one-time · check current figure",
            note = "Google Play Console's one-time developer registration fee.",
        ),
        WatchSeed(
            label = "Apple Developer Program",
            kind = WatchKind.RENEWAL,
            amountHint = "yearly · check current figure",
            note = "Apple Developer Program annual membership.",
        ),
        WatchSeed(
            label = "Garmin Connect IQ merchant account",
            kind = WatchKind.RENEWAL,
            amountHint = "check current figure",
            note = "Garmin Connect IQ merchant/developer account renewal.",
        ),
        WatchSeed(
            label = "Upload key / signing cert expiry",
            kind = WatchKind.EXPIRY,
            note = "Your app's upload key or signing certificate expiry date.",
        ),
        WatchSeed(
            label = "EEA commercial-seller status",
            kind = WatchKind.STATUS,
            note = "EU Digital Services Act trader/commercial-seller declaration status.",
        ),
    )
}
