package dev.aarso.data

import dev.aarso.domain.git.GitHost
import dev.aarso.domain.ide.ProjectScaffold
import dev.aarso.domain.ide.ScaffoldPublishApi

/**
 * Data-layer orchestration for the IDE last mile: create a repo on the user's host and
 * push a [ProjectScaffold] into it. Same shape as [BuildsRepo]/[IssueBoardRepo] — token
 * provider + [GitTransport], talks only to the user's host.
 *
 * **This has a real, outward side effect** (it creates a repository on the account), so
 * callers MUST gate it behind explicit user confirmation; it is never auto-invoked.
 * Network is owner-verified — there is no host in CI.
 */
class ScaffoldPublishRepo(
    private val transport: GitTransport,
    private val tokenProvider: (hostId: String) -> String?,
) {
    constructor(transport: GitTransport, hostStore: GitHostStore) : this(transport, hostStore::token)

    data class Result(
        val ok: Boolean,
        /** True once the repo create returned 2xx (so partial pushes are diagnosable). */
        val createdRepo: Boolean,
        val pushed: Int,
        val total: Int,
        val error: String? = null,
    )

    /**
     * Create [repoName] on [host] and push the files generated from [spec]. Returns a
     * [Result] describing how far it got (no exceptions for the caller to catch).
     */
    suspend fun publish(
        host: GitHost,
        spec: ProjectScaffold.AppSpec,
        repoName: String,
        private: Boolean = true,
    ): Result {
        val token = tokenProvider(host.id) ?: return Result(false, false, 0, 0, "no token for this host")

        val files = runCatching { ProjectScaffold.generate(spec) }.getOrElse {
            return Result(false, false, 0, 0, it.message ?: "invalid spec")
        }
        val total = files.size

        val create = runCatching { transport.execute(ScaffoldPublishApi.createRepo(host, repoName, private, token)) }
            .getOrNull() ?: return Result(false, false, 0, total, "create repo: network error")
        if (create.code !in 200..299) {
            return Result(false, false, 0, total, "create repo: HTTP ${create.code}")
        }

        var pushed = 0
        for (req in ScaffoldPublishApi.publishRequests(host, repoName, files, token)) {
            val r = runCatching { transport.execute(req) }.getOrNull()
                ?: return Result(false, true, pushed, total, "push failed after $pushed/$total")
            if (r.code !in 200..299) {
                return Result(false, true, pushed, total, "push HTTP ${r.code} after $pushed/$total")
            }
            pushed++
        }
        return Result(true, true, pushed, total, null)
    }
}
