package dev.aarso.domain.integrations

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SarifResultsTest {

    // Verbatim shape of ASSAY_REPO_CONTRACT_V1.md §4's worked example, wrapped in a minimal
    // real runs[] envelope (the doc's own excerpt is "non-normative... this is illustration").
    private fun sarifDoc(resultsJson: String) = JSONObject(
        """
        {
          "version": "2.1.0",
          "runs": [
            {
              "tool": { "driver": { "name": "Assay" } },
              "results": [$resultsJson]
            }
          ]
        }
        """.trimIndent()
    )

    private val wellFormedResult = """
        {
          "ruleId": "gitleaks.generic-api-key",
          "level": "error",
          "message": { "text": "Hardcoded credential-shaped string detected." },
          "locations": [
            { "physicalLocation": { "artifactLocation": { "uri": "app/src/main/java/dev/aarso/data/Config.kt" }, "region": { "startLine": 42 } } }
          ],
          "properties": {
            "scannerName": "Gitleaks",
            "ruleId": "generic-api-key",
            "provingTestRef": "01J9F0000000000000000PT2"
          }
        }
    """.trimIndent()

    @Test
    fun `parses the ASSAY_REPO_CONTRACT_V1 worked example exactly`() {
        val outcome = SarifParser.parse(sarifDoc(wellFormedResult))
        assertTrue(outcome.rejections.isEmpty())
        val result = outcome.results.single()
        assertEquals("Gitleaks", result.scannerName)
        assertEquals("generic-api-key", result.ruleIdFromProperties)
        assertEquals("01J9F0000000000000000PT2", result.provingTestRef)
        assertEquals("app/src/main/java/dev/aarso/data/Config.kt", result.locationUris.single())
        assertEquals("error", result.level)
    }

    @Test
    fun `a result missing properties scannerName is rejected individually, not the whole file`() {
        val missingScanner = """
            { "ruleId": "x", "level": "warning", "message": {"text": "t"}, "locations": [],
              "properties": { "ruleId": "generic-api-key" } }
        """.trimIndent()
        val outcome = SarifParser.parse(sarifDoc("$wellFormedResult, $missingScanner"))
        assertEquals(1, outcome.results.size) // the well-formed one still parsed
        assertEquals(1, outcome.rejections.size)
        assertEquals(1, outcome.rejections.single().resultIndex) // the SECOND result (index 1) is the bad one
        assertEquals("SCANNERNAME", outcome.rejections.single().missingField)
    }

    @Test
    fun `a result missing properties ruleId is rejected individually`() {
        val missingRuleId = """
            { "ruleId": "x", "level": "warning", "message": {"text": "t"}, "locations": [],
              "properties": { "scannerName": "Semgrep" } }
        """.trimIndent()
        val outcome = SarifParser.parse(sarifDoc(missingRuleId))
        assertTrue(outcome.results.isEmpty())
        assertEquals("RULEID", outcome.rejections.single().missingField)
    }

    @Test
    fun `an absent provingTestRef is never a rejection -- most findings have no proving test yet`() {
        val noProvingTest = """
            { "ruleId": "x", "level": "note", "message": {"text": "t"}, "locations": [],
              "properties": { "scannerName": "OSV-Scanner", "ruleId": "cve-2026-x" } }
        """.trimIndent()
        val outcome = SarifParser.parse(sarifDoc(noProvingTest))
        assertTrue(outcome.rejections.isEmpty())
        assertEquals(null, outcome.results.single().provingTestRef)
    }

    @Test
    fun `an empty runs array parses to zero results, not an error`() {
        val outcome = SarifParser.parse(JSONObject("""{"version": "2.1.0", "runs": []}"""))
        assertTrue(outcome.results.isEmpty() && outcome.rejections.isEmpty())
    }
}
