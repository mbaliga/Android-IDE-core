package dev.fonebrew.watch.core

/**
 * Maps a Play-services status code to a [LinkFailure]. Lives here, as plain integers, so the
 * classification is JVM-tested without the GMS classes (which only the `link/` package touches).
 *
 * The numbers are the documented, stable status codes: `WearableStatusCodes.TARGET_NODE_NOT_CONNECTED`
 * (4000); `ConnectionResult.SERVICE_MISSING` (1), `SERVICE_VERSION_UPDATE_REQUIRED` (2),
 * `SERVICE_DISABLED` (3), `SERVICE_INVALID` (9), `API_UNAVAILABLE` (16); and
 * `CommonStatusCodes.API_NOT_CONNECTED` (17).
 */
object LinkFailureClassifier {
    const val TARGET_NODE_NOT_CONNECTED = 4000

    private val API_UNAVAILABLE_CODES = setOf(1, 2, 3, 9, 16, 17)

    fun fromStatusCode(code: Int?): LinkFailure = when {
        code == TARGET_NODE_NOT_CONNECTED -> LinkFailure.NO_NODE
        code != null && code in API_UNAVAILABLE_CODES -> LinkFailure.API_UNAVAILABLE
        else -> LinkFailure.OTHER
    }
}
