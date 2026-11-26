package dev.fonebrew.domain.git

import org.json.JSONObject

/**
 * Outcome of *building* a publish-side request (branch / pull request / CI). Pure data: building
 * never does I/O. [Unsupported] names the missing capability on a host kind whose documented API has
 * no equivalent - we never guess an endpoint. [Invalid] is bad caller input. Neither carries a token.
 */
sealed interface GitBuild {
    data class Ready(val request: GitRequest) : GitBuild
    data class Unsupported(val kind: GitHostKind, val capability: String) : GitBuild {
        val message: String get() = "${kind.label} has no documented API for: $capability"
    }
    data class Invalid(val reason: String) : GitBuild
}

enum class GitApiErrorKind { UNAUTHORIZED, FORBIDDEN, RATE_LIMITED, NOT_FOUND, CONFLICT, UNPROCESSABLE, SERVER, MALFORMED, OTHER }

/** A typed host error. Parsers only ever see `(status, body)`, never the token, so nothing here can
 *  contain it. [message] is the host's own `message` field, bounded. */
data class GitApiError(val kind: GitApiErrorKind, val status: Int, val message: String)

/** Parse outcome: the value, or a typed [GitApiError]. */
sealed interface GitApiResult<out T> {
    data class Ok<T>(val value: T) : GitApiResult<T>
    data class Err(val error: GitApiError) : GitApiResult<Nothing>
}

internal object GitPublishSupport {
    const val MAX_MESSAGE = 200

    fun headers(host: GitHost, token: String, json: Boolean = false): Map<String, String> {
        val base = mapOf(
            "Authorization" to when (host.kind) {
                GitHostKind.GITHUB -> "Bearer $token"
                GitHostKind.GITEA -> "token $token"
            },
            "Accept" to when (host.kind) {
                GitHostKind.GITHUB -> "application/vnd.github+json"
                GitHostKind.GITEA -> "application/json"
            },
        )
        return if (json) base + ("Content-Type" to "application/json") else base
    }

    fun repoBase(host: GitHost) = "${GitContentsApi.apiBase(host)}/repos/${enc(host.owner)}/${enc(host.repo)}"

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Maps a non-success status (and best-effort body) to a typed error. */
    fun error(status: Int, body: String): GitApiError {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrDefault("").take(MAX_MESSAGE)
        val kind = when {
            status == 401 -> GitApiErrorKind.UNAUTHORIZED
            status == 429 -> GitApiErrorKind.RATE_LIMITED
            status == 403 && msg.contains("rate limit", ignoreCase = true) -> GitApiErrorKind.RATE_LIMITED
            status == 403 -> GitApiErrorKind.FORBIDDEN
            status == 404 -> GitApiErrorKind.NOT_FOUND
            status == 409 -> GitApiErrorKind.CONFLICT
            status == 422 -> GitApiErrorKind.UNPROCESSABLE
            status in 500..599 -> GitApiErrorKind.SERVER
            else -> GitApiErrorKind.OTHER
        }
        return GitApiError(kind, status, msg)
    }

    fun malformed(status: Int, what: String) =
        GitApiResult.Err(GitApiError(GitApiErrorKind.MALFORMED, status, what))

    /** Parses a JSON object body for a 2xx [okStatuses] response, else the typed error. */
    inline fun <T> parseObject(
        status: Int,
        body: String,
        okStatuses: Set<Int>,
        what: String,
        build: (JSONObject) -> T?,
    ): GitApiResult<T> {
        if (status !in okStatuses) return GitApiResult.Err(error(status, body))
        if (body.isBlank()) return malformed(status, "empty $what response")
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return malformed(status, "$what response is not JSON")
        val v = build(o) ?: return malformed(status, "$what response is missing required fields")
        return GitApiResult.Ok(v)
    }
}
