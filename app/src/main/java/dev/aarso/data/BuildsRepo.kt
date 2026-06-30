package dev.aarso.data

import dev.aarso.domain.builds.Build
import dev.aarso.domain.builds.BuildsApi
import dev.aarso.domain.builds.ChecksSummary
import dev.aarso.domain.git.GitContentsApi
import dev.aarso.domain.git.GitHost

/**
 * Data-layer facade over [BuildsApi]: fetches app-distribution artefacts from the
 * user's connected Git host and returns parsed [Build] lists. Follows the same
 * pattern as [GitBackup] and [GitBrowse] — injects [GitTransport] + a token
 * provider, returns empty/null when no host is wired up, and talks ONLY to the
 * user's own host.
 *
 * Two APK sources are tried in priority order:
 *  1. Release assets (newest-first, as returned by the host).
 *  2. Files on the dist-branch (`apk-dist` by default) read via the contents API.
 *
 * Network is owner-verified — there is no host to call in CI.
 *
 * @param tokenProvider Returns the decrypted token for a host ID, or null if none is
 *   stored. In production, pass `hostStore::token`; in tests, pass a lambda directly —
 *   this avoids touching Android SharedPreferences in JVM tests.
 */
class BuildsRepo(
    private val transport: GitTransport,
    private val tokenProvider: (hostId: String) -> String?,
) {

    /**
     * Convenience constructor for production use: token lookup delegates to
     * [GitHostStore.token] (Keystore-backed, never logged).
     */
    constructor(transport: GitTransport, hostStore: GitHostStore) :
        this(transport, hostStore::token)

    /**
     * List all APK builds visible on [host] under the current token.
     *
     * Priority: **releases** (richer metadata) then **dist-branch** contents. Both
     * lists are appended so the caller sees everything in one call — they can group
     * by [dev.aarso.domain.builds.BuildSource] if needed.
     *
     * Returns an empty list (not an error) when no token is stored for this host.
     */
    suspend fun listBuilds(host: GitHost): List<Build> {
        val token = tokenProvider(host.id) ?: return emptyList()

        val out = ArrayList<Build>()

        // 1. Release assets
        val relR = runCatching { transport.execute(BuildsApi.listReleases(host, token)) }.getOrNull()
        if (relR != null && relR.code in 200..299) {
            runCatching { BuildsApi.parseReleases(relR.body) }.getOrNull()?.let { out += it }
        }

        // 2. Dist-branch contents (the `apk-dist` pattern)
        val distBranch = DIST_BRANCH
        val distHost = host.copy(branch = distBranch)
        val dirR = runCatching { transport.execute(GitContentsApi.listDir(distHost, "", token)) }.getOrNull()
        if (dirR != null && dirR.code in 200..299) {
            runCatching { BuildsApi.parseDistBranchApks(dirR.body, distBranch) }.getOrNull()?.let { out += it }
        }

        return out
    }

    /**
     * Fetch the CI test verdict for a [build]'s branch ref. Returns [ChecksSummary.NONE]
     * on any error or when no host token is available — callers treat NONE as "no badge".
     */
    suspend fun checks(host: GitHost, build: Build): ChecksSummary {
        val token = tokenProvider(host.id) ?: return ChecksSummary.NONE
        val ref = build.branch.ifBlank { host.branch }
        val r = runCatching { transport.execute(BuildsApi.checks(host, ref, token)) }.getOrNull()
            ?: return ChecksSummary.NONE
        if (r.code !in 200..299) return ChecksSummary.NONE
        return runCatching { BuildsApi.parseChecks(r.body, host.kind) }.getOrDefault(ChecksSummary.NONE)
    }

    /**
     * Returns the download URL for a [build]'s APK asset, or null if the build has
     * no usable URL. The URL is always on the user's own host — not a third-party CDN.
     */
    fun findApkUrl(build: Build): String? = build.downloadUrl.takeIf { it.isNotBlank() }

    private companion object {
        /** Branch name used for the dist-branch pattern (`apk-dist` in this repo). */
        const val DIST_BRANCH = "apk-dist"
    }
}
