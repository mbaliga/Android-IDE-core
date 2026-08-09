package dev.aarso.domain.integrations

import org.json.JSONObject

/**
 * The SARIF 2.1.0 profile subset `docs/ratified/ASSAY_REPO_CONTRACT_V1.md` §4 pins (designed
 * there, not redefined here) -- every `runs[].results[]` entry, with its required `properties`
 * bag: `scannerName`/`ruleId` (kept explicit in `properties`, not resolved from SARIF's own
 * top-level `rules[]` catalog -- see that section's rationale) and optional `provingTestRef`.
 * "The full SARIF 2.1.0 object model is out of scope to redefine here" (same doc) -- this parses
 * only the fields the profile actually requires, not a general SARIF library.
 */
data class SarifResult(
    val ruleId: String,
    val level: String,
    val messageText: String,
    val locationUris: List<String>,
    val scannerName: String,
    val ruleIdFromProperties: String,
    val provingTestRef: String?,
)

/** One `results[]` entry that failed the profile's own required-`properties` check (per-result, not whole-file, per §4). */
data class SarifResultRejection(val resultIndex: Int, val missingField: String)

data class SarifParseOutcome(val results: List<SarifResult>, val rejections: List<SarifResultRejection>)

object SarifParser {

    /**
     * Parses `runs[0].results[]` (Assay's `findings.sarif` is always single-run, per the worked
     * example in `ASSAY_REPO_CONTRACT_V1.md` §4). A result missing `properties.scannerName` or
     * `properties.ruleId` is rejected individually (`missingField` names which one) -- the rest of
     * the file's results are still parsed, matching the doc's "not a whole-file rejection" rule.
     * `provingTestRef` absence is never a rejection (most findings have no proving test yet).
     */
    fun parse(sarifJson: JSONObject): SarifParseOutcome {
        val runs = sarifJson.optJSONArray("runs")
        val results = runs?.optJSONObject(0)?.optJSONArray("results") ?: org.json.JSONArray()
        val parsed = mutableListOf<SarifResult>()
        val rejections = mutableListOf<SarifResultRejection>()

        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val properties = r.optJSONObject("properties")
            val scannerName = properties?.optString("scannerName")?.takeIf { it.isNotBlank() }
            val ruleIdFromProperties = properties?.optString("ruleId")?.takeIf { it.isNotBlank() }

            if (scannerName == null) {
                rejections += SarifResultRejection(i, "SCANNERNAME")
                continue
            }
            if (ruleIdFromProperties == null) {
                rejections += SarifResultRejection(i, "RULEID")
                continue
            }

            val locations = r.optJSONArray("locations")
            val locationUris = if (locations != null) {
                (0 until locations.length()).mapNotNull { idx ->
                    locations.optJSONObject(idx)
                        ?.optJSONObject("physicalLocation")
                        ?.optJSONObject("artifactLocation")
                        ?.optString("uri")
                        ?.takeIf { it.isNotBlank() }
                }
            } else emptyList()

            parsed += SarifResult(
                ruleId = r.optString("ruleId"),
                level = r.optString("level"),
                messageText = r.optJSONObject("message")?.optString("text").orEmpty(),
                locationUris = locationUris,
                scannerName = scannerName,
                ruleIdFromProperties = ruleIdFromProperties,
                provingTestRef = properties.optString("provingTestRef").takeIf { it.isNotBlank() }
            )
        }
        return SarifParseOutcome(parsed, rejections)
    }
}
