package dev.aarso.data

import dev.aarso.domain.remote.ExecRequest
import dev.aarso.domain.remote.Identity
import dev.aarso.domain.remote.KnownHosts
import dev.aarso.domain.remote.RemoteHost
import dev.aarso.domain.remote.RemoteSessionDriver
import dev.aarso.domain.remote.RemoteTransport
import dev.aarso.domain.remote.Trust

/**
 * Runs a **device recipe** (`domain/device/DeviceRecipes` → an `ExecRequest`) on a host over the
 * SSH spine, streaming the host's raw output (a **watched object** — verbatim, never paraphrased).
 * This is the wire between the headless recipe models and the real `RemoteSessionDriver`.
 *
 * Trust stays the user's: [exec] only proceeds on an already-vetted host (pin it once via the
 * Remote screen); an unknown/changed key is refused here rather than silently accepted.
 * Entirely owner-verified — there is no SSH host or board in CI.
 */
class DeviceRepo(private val newTransport: () -> RemoteTransport) {

    /** Open → run [recipe] → close. [onOutput] receives raw stdout/stderr as it streams. */
    suspend fun exec(
        host: RemoteHost,
        identity: Identity,
        knownHosts: KnownHosts,
        recipe: ExecRequest,
        onOutput: (String) -> Unit,
    ): Result<Int> = runCatching {
        val driver = RemoteSessionDriver(newTransport(), knownHosts)
        try {
            driver.open(host, identity) { verdict ->
                // Only proceed on an already-pinned host; the trust decision belongs to the
                // Remote screen, not buried in a device action.
                verdict is Trust.Vetted
            }
            val result = driver.exec(recipe) { chunk -> onOutput(String(chunk.bytes)) }
            result.exitCode
        } finally {
            runCatching { driver.close() }
        }
    }
}
