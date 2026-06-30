package dev.aarso.domain.builds

/**
 * A buildable artifact of an app you develop on Aarso (docs/design/app-distribution.md):
 * an APK produced by your repo's CI, listed so it can be installed in-app without
 * leaving for the browser. Pure domain; JVM-tested. The artifact comes from one of
 * three watched-host sources — a Release asset, a CI artifact, or a file on a
 * dist-branch (the `apk-dist` pattern).
 */
data class Build(
    val id: String,
    /** Human version: a release tag (`v2.0`), or a short commit for a dist-branch. */
    val version: String,
    /** The artifact file name, e.g. `aarso-sd.apk`. */
    val name: String,
    val branch: String,
    /** ISO timestamp from the host, or blank if the source carries none. */
    val createdAt: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val source: BuildSource,
)

enum class BuildSource { RELEASE_ASSET, CI_ARTIFACT, DIST_BRANCH }

/** The CI verdict attached to a build (the test badge: ✓ / ✗ / building). */
data class ChecksSummary(
    val passed: Int,
    val failed: Int,
    val pending: Int,
    val conclusion: CheckConclusion,
) {
    companion object {
        val NONE = ChecksSummary(0, 0, 0, CheckConclusion.NONE)
    }
}

enum class CheckConclusion { SUCCESS, FAILURE, PENDING, NONE }
