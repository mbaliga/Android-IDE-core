package dev.aarso.domain.git

/**
 * A connected Git host the user owns — the foundation shared by tree-sovereignty
 * and the coding assistant (docs/design/). Every host here is a **watched object**:
 * opt-in, visible, and the app talks only to it. The token is NOT stored here — it
 * lives encrypted in the Android Keystore (see data/GitHostStore).
 *
 * v1 supports GitHub and Gitea/Forgejo (they share the `/contents/` REST API).
 * Transport is the host REST API (owner decision), not JGit.
 */
enum class GitHostKind(val label: String, val needsBaseUrl: Boolean) {
    GITHUB("GitHub", false),
    GITEA("Gitea / Forgejo", true),
}

data class GitHost(
    val id: String,
    val displayName: String,
    val kind: GitHostKind,
    /** Instance URL for Gitea (e.g. https://gitea.example.com); ignored for GitHub. */
    val baseUrl: String,
    val owner: String,
    val repo: String,
    val branch: String,
    val authorName: String,
    val authorEmail: String,
)
