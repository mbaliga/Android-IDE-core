package dev.aarso.ui.develop

import androidx.compose.runtime.Composable

/**
 * S2 seam — the extension point that keeps the open core's Develop room from
 * depending on the paid Studio layer.
 *
 * The open core's Develop room ships its own free tabs (Cost / Agent / Devices). The
 * **Launch** and **Builds** tabs are part of the paid "ship & sell" Studio layer, so
 * core does not reference them directly: an above-core layer *installs* a provider, and
 * the Develop room renders core tabs **then** any contributed tabs. In the bare open
 * core the provider is empty (no Launch/Builds), which is exactly the free shape.
 *
 * Once the Studio layer is carved into its own module/repo, the registration moves there
 * with it; nothing in core changes. (See `docs/EXTRACTION_PLAN.md` §3.)
 */
class DevelopTab(
    /** Tab label shown in the Develop room's tab strip. */
    val label: String,
    /** The tab body. */
    val content: @Composable () -> Unit,
)

/** Holds the (optional) above-core contribution of extra Develop tabs. */
object DevelopTabs {
    /** Empty in the bare open core; the Studio layer installs Launch/Builds via [install]. */
    var provider: () -> List<DevelopTab> = { emptyList() }
        private set

    /** Install the contributed tabs. Idempotent; replaces any prior provider. */
    fun install(provider: () -> List<DevelopTab>) {
        this.provider = provider
    }
}
