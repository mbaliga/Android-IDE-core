package dev.fonebrew.domain.gitcore.transport

/**
 * Which hosting convention a token-as-password should follow. Presentation only: the engine
 * (pkt-line, packs, refs) is host-agnostic and never branches on this.
 */
enum class GitHostFlavor(val label: String) {
    GITHUB("GitHub"), GITLAB("GitLab"), GITEA("Gitea / Forgejo"), BITBUCKET("Bitbucket"), OTHER("Other");

    /**
     * The HTTP Basic username to pair with an access token. [account] is the user's own login when
     * known. GitHub accepts any non-empty name with a token; GitLab wants `oauth2` for OAuth/PAT
     * tokens when no login is given; Gitea/Forgejo want the account login; Bitbucket's repository
     * access tokens use `x-token-auth`; elsewhere fall back to the login or the neutral `git`.
     */
    fun tokenUsername(account: String?): String {
        val acc = account?.takeIf { it.isNotBlank() }
        return when (this) {
            GITHUB -> acc ?: "x-access-token"
            GITLAB -> acc ?: "oauth2"
            GITEA -> acc ?: "git"
            BITBUCKET -> acc ?: "x-token-auth"
            OTHER -> acc ?: "git"
        }
    }

    companion object {
        /** A guess from the host name alone; self-hosted instances are [OTHER] unless the user says otherwise. */
        fun fromHost(host: String): GitHostFlavor {
            val h = host.lowercase().removePrefix("www.")
            return when {
                h == "github.com" || h.endsWith(".github.com") -> GITHUB
                h == "gitlab.com" || h.startsWith("gitlab.") -> GITLAB
                h == "bitbucket.org" -> BITBUCKET
                h == "codeberg.org" || h.startsWith("gitea.") || h.startsWith("forgejo.") -> GITEA
                else -> OTHER
            }
        }
    }
}
