// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.loop

import dev.fonebrew.contracts.common.IntegrityRef
import dev.fonebrew.contracts.common.ProducerRef
import dev.fonebrew.contracts.loops.CapabilityRequest
import dev.fonebrew.contracts.loops.CompatibilityDeclaration
import dev.fonebrew.contracts.loops.FindingSeverity
import dev.fonebrew.contracts.loops.LoopPackageIdentity
import dev.fonebrew.contracts.loops.LoopPackageManifest
import dev.fonebrew.contracts.loops.ManifestProvenance
import dev.fonebrew.contracts.loops.PackageFileClassification
import dev.fonebrew.contracts.loops.PackageInventoryEntry
import dev.fonebrew.contracts.loops.ValidationNamespace
import dev.fonebrew.contracts.loops.ValidationReportFinding
import dev.fonebrew.domain.bpmn.BpmnArchive
import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.bpmn.Bounds
import dev.fonebrew.domain.contracts.Digest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant

/**
 * Encodes/decodes a Loop as an importable, exportable package document, and runs the local,
 * on-device content-safety scan every import MUST pass before a person ever reviews it
 * (`LOOP_PACKAGE_SPEC.md` §6-§7, `loop-validation-rules.v1.json` LOOP-PKG-001/002/003/006/008).
 *
 * HONEST SCOPE NOTE (do not remove): this is deliberately NOT the frozen `.floop` ZIP container
 * (`schemas/loops/registries/floop-container-format.v1.json`) — that byte format, its
 * `fb-loop-canon-1` RFC 8785 canonicalization, and Ed25519 publisher signing are a materially
 * larger undertaking (a certified JCS canonicalizer, a ZIP structural-safety parser, Keystore
 * signing key provisioning) than this work package's scope. What this codec produces instead is
 * a single canonical JSON document — the `.floop.json` extension CLAUDE.md's task brief names as
 * the fallback when a byte format isn't fully prescribed, and `floop-container-format.v1.json`
 * itself says the public-facing extension is still `FB-RAT-LBX-010` EXPERIMENTAL. The
 * `LoopPackageManifest`/`PackageInventoryEntry`/adversarial-class vocabulary this file implements
 * against is the REAL one (`schemas/loops/loop-package-manifest.schema.json`,
 * `loop-validation-rules.v1.json` LOOP-PKG-001..008) — only the outer container bytes are
 * simplified, not the safety model. A package built here MUST NOT be presented as a signed,
 * marketplace-publishable `.floop` release (`LOOP_PACKAGE_SPEC.md` §10-§11) — it is always
 * `UNSIGNED` (no publisher key infrastructure exists in this codebase) and always a local/direct
 * transfer, never a marketplace listing.
 */
object LoopPackageCodec {

    const val PACKAGE_SCHEMA_VERSION = "1.0.0"
    const val ENGINE_VERSION_RANGE = ">=1.0.0 <2.0.0"
    const val PACKAGE_FILE_EXTENSION = ".floop.json"
    const val PACKAGE_MEDIA_TYPE = "application/vnd.fonebrew.loop-package+json"

    /** Local safety ceiling on the whole package document (FB-RAT-PKG-010 — calibratable, not a frozen limit). */
    private const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024

    /** A parse failure or a content-safety rejection PARSED_VALIDATED found — rejects the whole package (§3, §20). */
    class UnsafePackageException(message: String, val findings: List<ValidationReportFinding> = emptyList()) : Exception(message)

    /** What decode() hands back for the preview/authority-review screen and for re-saving as a Loop. */
    data class DecodedLoopPackage(
        val manifest: LoopPackageManifest,
        val graph: BpmnGraph,
        val objective: String,
        val readme: String?,
        val licenseText: String?,
        val authorName: String?,
    )

    /** What LoopRoom's export dialog collects before calling [encode]. */
    data class ExportRequest(
        val graph: BpmnGraph,
        val objective: String,
        val loopId: String,
        val semanticVersion: String,
        val licenseId: String?,
        val authorName: String?,
        val appVersion: String,
        val readme: String? = null,
        val licenseText: String? = null,
    )

    // =====================================================================================
    // Encode
    // =====================================================================================

    fun encode(request: ExportRequest): ByteArray {
        val definitionJson = graphToJson(request.graph, request.objective)
        val defBytes = definitionJson.toString().toByteArray(Charsets.UTF_8)
        val bpmnXml = BpmnArchive.write(request.graph)
        val bpmnBytes = bpmnXml.toByteArray(Charsets.UTF_8)

        val inventory = mutableListOf<PackageInventoryEntry>()
        val defDigest = Digest.of(defBytes)
        inventory += PackageInventoryEntry("definition.json", defDigest.byteLength, defDigest.digestHex, PackageFileClassification.semanticFile)
        val bpmnDigest = Digest.of(bpmnBytes)
        inventory += PackageInventoryEntry("definition.bpmn", bpmnDigest.byteLength, bpmnDigest.digestHex, PackageFileClassification.derivedFile)

        var readmeBytes: ByteArray? = null
        request.readme?.takeIf { it.isNotBlank() }?.let { readme ->
            readmeBytes = readme.toByteArray(Charsets.UTF_8)
            val d = Digest.of(readmeBytes!!)
            inventory += PackageInventoryEntry("docs/README.md", d.byteLength, d.digestHex, PackageFileClassification.semanticFile)
        }
        var licenseBytes: ByteArray? = null
        request.licenseText?.takeIf { it.isNotBlank() }?.let { text ->
            licenseBytes = text.toByteArray(Charsets.UTF_8)
            val d = Digest.of(licenseBytes!!)
            inventory += PackageInventoryEntry("LICENSE", d.byteLength, d.digestHex, PackageFileClassification.semanticFile)
        }

        val contentDigest = computeContentDigest(inventory)

        val manifest = LoopPackageManifest(
            schemaVersion = PACKAGE_SCHEMA_VERSION,
            packageIdentity = LoopPackageIdentity(request.loopId, request.semanticVersion),
            capabilityRequests = emptyList(), // honest: no fb.* capability system is wired to loop nodes yet
            compatibilityDeclaration = CompatibilityDeclaration(ENGINE_VERSION_RANGE),
            packageInventory = inventory,
            packageContentDigest = contentDigest,
            signatureRef = null, // honest: no publisher signing key infrastructure exists (LOOP_PACKAGE_SPEC.md §11 UNSIGNED path)
            license = request.licenseId,
            provenance = ManifestProvenance(
                producer = ProducerRef(name = "Fonebrew", version = request.appVersion),
                sourceDraftRevision = null,
                createdAtUtc = Instant.now(),
            ),
        )

        val doc = JSONObject()
        doc.put("packageFormat", "fonebrew-loop-package-document")
        doc.put("packageFormatVersion", "1.0.0")
        doc.put("manifest", manifestToJson(manifest, request.authorName))
        doc.put("definition", definitionJson)
        doc.put("definitionBpmnXml", bpmnXml)
        request.readme?.takeIf { it.isNotBlank() }?.let { doc.put("readme", it) }
        request.licenseText?.takeIf { it.isNotBlank() }?.let { doc.put("licenseText", it) }
        return doc.toString(2).toByteArray(Charsets.UTF_8)
    }

    /** packageContentDigest per LOOP_PACKAGE_SPEC.md §4: SHA-256 over the sorted (path, byteLength, sha256)
     *  tuple list of semanticFile entries. Sorted by path as UTF-16 code units (String.compareTo), matching
     *  floop-container-format.v1.json's entrySortOrder — an honest approximation of fb-loop-canon-1 for this
     *  restricted (string/int only, no floats) tuple shape, not a certified JCS implementation. */
    private fun computeContentDigest(inventory: List<PackageInventoryEntry>): IntegrityRef {
        val semantic = inventory.filter { it.classification == PackageFileClassification.semanticFile }.sortedBy { it.path }
        val arr = JSONArray()
        for (e in semantic) {
            arr.put(JSONObject().put("path", e.path).put("byteLength", e.byteLength).put("sha256", e.sha256))
        }
        return Digest.ofUtf8(arr.toString())
    }

    // =====================================================================================
    // Decode
    // =====================================================================================

    /** Full decode + content-safety scan. Throws [UnsafePackageException] on any ERROR-severity finding
     *  or structural parse failure — this is also what [LoopInstallationDriver]'s `parseManifest` callback
     *  should call, so a driver-level REJECTED_UNSAFE and this codec's own rejection are the same check. */
    fun decode(bytes: ByteArray): DecodedLoopPackage {
        if (bytes.size > MAX_DOCUMENT_BYTES) {
            throw UnsafePackageException(
                "Package document is ${bytes.size} bytes, exceeding the local safety limit of $MAX_DOCUMENT_BYTES bytes.",
                listOf(finding("LOOP-PKG-003", FindingSeverity.ERROR, "document", "Package document exceeds the local decompressed-size safety limit (FB-RAT-PKG-010).")),
            )
        }
        val doc = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw UnsafePackageException("Not a valid Fonebrew loop package document: ${e.message}")
        }
        val manifestObj = doc.optJSONObject("manifest")
            ?: throw UnsafePackageException("Package document is missing its required 'manifest' object.")
        val definitionObj = doc.optJSONObject("definition")
            ?: throw UnsafePackageException("Package document is missing its required 'definition' object.")

        val manifest = try {
            manifestFromJson(manifestObj)
        } catch (e: IllegalArgumentException) {
            // Every LoopPackageManifest/CapabilityRequest/etc. init{} require() surfaces here —
            // covers the unsupported-schemaVersion-major and missing-egress-allowlist (LOOP-PKG-007) classes.
            throw UnsafePackageException("Package manifest failed validation: ${e.message}")
        } catch (e: JSONException) {
            throw UnsafePackageException("Package manifest is malformed: ${e.message}")
        }

        val pathFindings = findPathIssues(manifest.packageInventory)
        val payloadFindings = findForbiddenPayloads(manifest.packageInventory)
        if (pathFindings.isNotEmpty() || payloadFindings.isNotEmpty()) {
            throw UnsafePackageException(
                "Package inventory failed adversarial safety checks.",
                pathFindings + payloadFindings,
            )
        }

        val (graph, objective) = try {
            graphFromJson(definitionObj)
        } catch (e: Exception) {
            throw UnsafePackageException("Package definition is malformed: ${e.message}")
        }

        val readme = doc.optString("readme", "").ifBlank { null }
        val licenseText = doc.optString("licenseText", "").ifBlank { null }
        val authorName = manifestObj.optJSONObject("provenance")?.optString("authorName", "")?.ifBlank { null }

        val contentFindings = scanContentSafety(graph, objective, readme, licenseText, manifest)
        val hardFindings = contentFindings.filter { it.severity == FindingSeverity.ERROR }
        if (hardFindings.isNotEmpty()) {
            throw UnsafePackageException(
                "Package content failed the local safety scan (secret-like material or disallowed invisible Unicode).",
                hardFindings,
            )
        }

        return DecodedLoopPackage(manifest, graph, objective, readme, licenseText, authorName)
    }

    /** Non-throwing scan for the review screen: structural findings PLUS heuristic warnings
     *  (LOOP-PKG-008, never blocking) that [decode] itself does not surface since they never reject. */
    fun scan(bytes: ByteArray): List<ValidationReportFinding> {
        val decoded = try {
            decode(bytes)
        } catch (e: UnsafePackageException) {
            return e.findings.ifEmpty { listOf(finding("LOOP-PKG-003", FindingSeverity.ERROR, "document", e.message ?: "Package rejected.")) }
        }
        return scanContentSafety(decoded.graph, decoded.objective, decoded.readme, decoded.licenseText, decoded.manifest) +
            scanPromptRisk(decoded.graph, decoded.objective, decoded.readme)
    }

    // =====================================================================================
    // definition.json <-> BpmnGraph
    // =====================================================================================

    private fun graphToJson(g: BpmnGraph, objective: String): JSONObject {
        val nodesArr = JSONArray()
        for (n in g.nodes) {
            val ext = JSONObject()
            for ((k, v) in n.ext) ext.put(k, v)
            nodesArr.put(
                JSONObject()
                    .put("id", n.id).put("kind", n.kind.name).put("name", n.name)
                    .put("x", n.bounds.x).put("y", n.bounds.y)
                    .put("width", n.bounds.width).put("height", n.bounds.height)
                    .put("ext", ext),
            )
        }
        val edgesArr = JSONArray()
        for (e in g.edges) {
            edgesArr.put(
                JSONObject()
                    .put("id", e.id).put("sourceId", e.sourceId).put("targetId", e.targetId)
                    .put("name", e.name ?: JSONObject.NULL)
                    .put("condition", e.condition ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("id", g.id).put("name", g.name).put("objective", objective)
            .put("nodes", nodesArr).put("edges", edgesArr)
    }

    private fun graphFromJson(o: JSONObject): Pair<BpmnGraph, String> {
        val nodesArr = o.optJSONArray("nodes") ?: JSONArray()
        val nodes = (0 until nodesArr.length()).map { i ->
            val n = nodesArr.getJSONObject(i)
            val kind = BpmnNodeKind.entries.firstOrNull { it.name == n.getString("kind") }
                ?: throw IllegalArgumentException("Unknown node kind '${n.optString("kind")}' at node '${n.optString("id")}'.")
            val extObj = n.optJSONObject("ext") ?: JSONObject()
            val ext = extObj.keys().asSequence().associateWith { extObj.getString(it) }
            BpmnNode(
                id = n.getString("id"), kind = kind, name = n.optString("name", ""),
                bounds = Bounds(n.optDouble("x", 0.0), n.optDouble("y", 0.0), n.optDouble("width", 130.0), n.optDouble("height", 64.0)),
                ext = ext,
            )
        }
        val edgesArr = o.optJSONArray("edges") ?: JSONArray()
        val edges = (0 until edgesArr.length()).map { i ->
            val e = edgesArr.getJSONObject(i)
            BpmnEdge(
                id = e.getString("id"), sourceId = e.getString("sourceId"), targetId = e.getString("targetId"),
                name = if (e.isNull("name")) null else e.optString("name"),
                condition = if (e.isNull("condition")) null else e.optString("condition"),
            )
        }
        val graph = BpmnGraph(id = o.getString("id"), name = o.optString("name", ""), nodes = nodes, edges = edges)
        return graph to o.optString("objective", "")
    }

    // =====================================================================================
    // manifest.json <-> LoopPackageManifest
    // =====================================================================================

    private fun manifestToJson(m: LoopPackageManifest, authorName: String?): JSONObject {
        val capArr = JSONArray()
        for (c in m.capabilityRequests) {
            val allow = JSONArray(); c.egressAllowlist.forEach { allow.put(it) }
            capArr.put(
                JSONObject().put("capabilityId", c.capabilityId)
                    .put("justification", c.justification ?: JSONObject.NULL)
                    .put("egressAllowlist", allow),
            )
        }
        val invArr = JSONArray()
        for (e in m.packageInventory) {
            invArr.put(JSONObject().put("path", e.path).put("byteLength", e.byteLength).put("sha256", e.sha256).put("classification", e.classification.name))
        }
        val provenance = m.provenance?.let { p ->
            JSONObject()
                .put("producer", p.producer?.let { JSONObject().put("name", it.name).put("version", it.version).put("instanceId", it.instanceId ?: JSONObject.NULL) } ?: JSONObject.NULL)
                .put("sourceDraftRevision", p.sourceDraftRevision ?: JSONObject.NULL)
                .put("createdAtUtc", p.createdAtUtc?.toString() ?: JSONObject.NULL)
                .apply { if (authorName != null) put("authorName", authorName) }
        } ?: JSONObject.NULL

        return JSONObject()
            .put("schemaVersion", m.schemaVersion)
            .put("packageIdentity", JSONObject().put("loopId", m.packageIdentity.loopId).put("semanticVersion", m.packageIdentity.semanticVersion))
            .put("capabilityRequests", capArr)
            .put("compatibilityDeclaration", JSONObject().put("engineVersionRange", m.compatibilityDeclaration.engineVersionRange))
            .put("packageInventory", invArr)
            .put("packageContentDigest", JSONObject().put("algorithm", m.packageContentDigest.algorithm.wireValue).put("digestHex", m.packageContentDigest.digestHex).put("byteLength", m.packageContentDigest.byteLength))
            .put("signatureRef", m.signatureRef?.let { JSONObject().put("path", it.path).put("sha256", it.sha256) } ?: JSONObject.NULL)
            .put("license", m.license ?: JSONObject.NULL)
            .put("provenance", provenance)
    }

    private fun manifestFromJson(o: JSONObject): LoopPackageManifest {
        val schemaVersion = o.getString("schemaVersion")
        val identityObj = o.getJSONObject("packageIdentity")
        val identity = LoopPackageIdentity(identityObj.getString("loopId"), identityObj.getString("semanticVersion"))

        val capArr = o.optJSONArray("capabilityRequests") ?: JSONArray()
        val capabilities = (0 until capArr.length()).map { i ->
            val c = capArr.getJSONObject(i)
            val allowArr = c.optJSONArray("egressAllowlist") ?: JSONArray()
            val allow = (0 until allowArr.length()).map { allowArr.getString(it) }
            CapabilityRequest(
                capabilityId = c.getString("capabilityId"),
                justification = if (c.isNull("justification")) null else c.optString("justification"),
                egressAllowlist = allow,
            )
        }

        val compatObj = o.getJSONObject("compatibilityDeclaration")
        val compat = CompatibilityDeclaration(compatObj.getString("engineVersionRange"), compatObj.optString("notes", "").ifBlank { null })

        val invArr = o.getJSONArray("packageInventory")
        val inventory = (0 until invArr.length()).map { i ->
            val e = invArr.getJSONObject(i)
            PackageInventoryEntry(
                path = e.getString("path"), byteLength = e.getLong("byteLength"), sha256 = e.getString("sha256"),
                classification = PackageFileClassification.valueOf(e.getString("classification")),
            )
        }

        val digestObj = o.getJSONObject("packageContentDigest")
        val digest = IntegrityRef(
            algorithm = dev.fonebrew.contracts.common.DigestAlgorithm.fromWireValue(digestObj.getString("algorithm")),
            digestHex = digestObj.getString("digestHex"), byteLength = digestObj.getLong("byteLength"),
        )

        val sigRef = if (o.isNull("signatureRef") || !o.has("signatureRef")) null else o.optJSONObject("signatureRef")?.let {
            dev.fonebrew.contracts.loops.ManifestSignatureRef(it.getString("path"), it.getString("sha256"))
        }

        val license = if (o.isNull("license")) null else o.optString("license", "").ifBlank { null }

        val provenance = o.optJSONObject("provenance")?.let { p ->
            ManifestProvenance(
                producer = p.optJSONObject("producer")?.let { pr -> ProducerRef(pr.getString("name"), pr.getString("version"), pr.optString("instanceId", "").ifBlank { null }) },
                sourceDraftRevision = p.optString("sourceDraftRevision", "").ifBlank { null },
                createdAtUtc = p.optString("createdAtUtc", "").ifBlank { null }?.let { Instant.parse(it) },
            )
        }

        return LoopPackageManifest(
            schemaVersion = schemaVersion, packageIdentity = identity, capabilityRequests = capabilities,
            compatibilityDeclaration = compat, packageInventory = inventory, packageContentDigest = digest,
            signatureRef = sigRef, license = license, provenance = provenance,
        )
    }

    // =====================================================================================
    // Adversarial checks — loop-validation-rules.v1.json LOOP-PKG-* (structural)
    // =====================================================================================

    /** LOOP-PKG-003: path traversal / duplicate-normalized-path across packageInventory entries
     *  (`floop-container-format.v1.json` pathRules) — message text mirrors the ratified fixtures
     *  under `fixtures/loops/loop-package-manifest/adversarial/` exactly. */
    private fun findPathIssues(inventory: List<PackageInventoryEntry>): List<ValidationReportFinding> {
        val findings = mutableListOf<ValidationReportFinding>()
        val seenNormalized = mutableMapOf<String, String>()
        for (e in inventory) {
            val p = e.path
            if (p.startsWith("/") || p.split('/').any { it == ".." }) {
                findings += finding("LOOP-PKG-003", FindingSeverity.ERROR, "file", "Path or canonicalization error at '$p': path traversal ('../' segment present).")
            }
            val normalized = Normalizer.normalize(p, Normalizer.Form.NFC).lowercase()
            val prior = seenNormalized[normalized]
            if (prior != null && prior != p) {
                findings += finding("LOOP-PKG-003", FindingSeverity.ERROR, "file", "Path or canonicalization error at '$p': duplicate normalized paths ('$prior' and '$p' both normalize to the same byte-compared path).")
            } else {
                seenNormalized[normalized] = p
            }
        }
        return findings
    }

    private val FORBIDDEN_EXTENSIONS = setOf(".dex", ".jar", ".so", ".exe", ".class", ".apk", ".bat", ".cmd", ".ps1", ".msi", ".dll", ".dylib")

    /** LOOP-PKG-002: forbidden executable payload by inventory path extension. */
    private fun findForbiddenPayloads(inventory: List<PackageInventoryEntry>): List<ValidationReportFinding> =
        inventory.filter { e -> FORBIDDEN_EXTENSIONS.any { e.path.lowercase().endsWith(it) } }
            .map { finding("LOOP-PKG-002", FindingSeverity.ERROR, "file", "File '${it.path}' is a forbidden executable payload (DEX/JAR/native binary/script interpreter directive/plugin installer).") }

    // Unicode Tags (supplementary plane), bidi controls, zero-width/format chars, and BMP Private
    // Use Area — the codepoint classes LOOP-PKG-006 names. Supplementary-plane PUA-A/PUA-B are a
    // known gap (documented, not scanned) — this covers the BMP case, the one every cited
    // precedent (GlassWorm, Rules-File-Backdoor) actually used.
    private val INVISIBLE_UNICODE = Regex("[\\u202A-\\u202E\\u2066-\\u2069\\u200B-\\u200F\\uFEFF\\uE000-\\uF8FF]|[\\x{E0000}-\\x{E007F}]")

    private val SECRET_PATTERNS = listOf(
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("sk-[A-Za-z0-9]{20,}"),
        Regex("ghp_[A-Za-z0-9]{30,}"),
        Regex("xox[baprs]-[A-Za-z0-9-]{10,}"),
        Regex("-----BEGIN( RSA| EC| OPENSSH)? PRIVATE KEY-----"),
        Regex("(?i)\"(api[_-]?key|secret|password|token)\"\\s*:\\s*\"[^\"<>{}]{8,}\""),
    )

    /** LOOP-PKG-001 (secret-like) and LOOP-PKG-006 (invisible Unicode, non-dismissible) — both
     *  ERROR/reject, scanned over every model- or human-visible string this package carries:
     *  node labels/roles/instructions, the objective, docs, license text, and capability
     *  justifications. Heuristic, not the named `gitleaks` tool (§7 cites gitleaks specifically;
     *  no such dependency exists in this module's graph, and adding a third-party secret scanner
     *  is out of this work package's scope) — documented honestly rather than claimed as
     *  equivalent. */
    private fun scanContentSafety(
        graph: BpmnGraph, objective: String, readme: String?, licenseText: String?, manifest: LoopPackageManifest,
    ): List<ValidationReportFinding> {
        val findings = mutableListOf<ValidationReportFinding>()
        val locations = mutableListOf<Pair<String, String>>() // location label, text
        locations += "objective" to objective
        readme?.let { locations += "docs/README.md" to it }
        licenseText?.let { locations += "LICENSE" to it }
        for (n in graph.nodes) {
            locations += "node '${n.id}'.name" to n.name
            n.ext["systemPrompt"]?.let { locations += "node '${n.id}'.systemPrompt" to it }
        }
        for (c in manifest.capabilityRequests) c.justification?.let { locations += "capabilityRequest '${c.capabilityId}'.justification" to it }

        for ((where, text) in locations) {
            if (text.isBlank()) continue
            if (SECRET_PATTERNS.any { it.containsMatchIn(text) }) {
                findings += finding("LOOP-PKG-001", FindingSeverity.ERROR, "string", "String at '$where' matches a secret-like pattern (API key, private key, token).")
            }
            if (INVISIBLE_UNICODE.containsMatchIn(text)) {
                findings += finding("LOOP-PKG-006", FindingSeverity.ERROR, "string", "Model- or human-visible string at '$where' contains disallowed invisible/control Unicode not individually declared and justified in the manifest.", nonDismissible = true)
            }
        }
        return findings
    }

    private val PROMPT_RISK_MARKERS = listOf(
        "ignore previous instructions" to "model-directed imperative",
        "ignore all previous" to "model-directed imperative",
        "disregard the above" to "model-directed imperative",
        "~/.ssh" to "sensitive-path reference",
        ".env" to "sensitive-path reference",
        "send it to" to "exfiltration verb+destination",
        "exfiltrate" to "exfiltration verb+destination",
        "upload it to http" to "exfiltration verb+destination",
        "<!--" to "markdown/HTML-comment-smuggled instruction",
    )

    /** LOOP-PKG-008: WARNING-only, human-review-required heuristic — never blocks (§7). */
    private fun scanPromptRisk(graph: BpmnGraph, objective: String, readme: String?): List<ValidationReportFinding> {
        val findings = mutableListOf<ValidationReportFinding>()
        val locations = mutableListOf<Pair<String, String>>("objective" to objective)
        readme?.let { locations += "docs/README.md" to it }
        for (n in graph.nodes) n.ext["systemPrompt"]?.let { locations += "node '${n.id}'.systemPrompt" to it }
        for ((where, text) in locations) {
            val lower = text.lowercase()
            for ((marker, cls) in PROMPT_RISK_MARKERS) {
                if (lower.contains(marker)) {
                    findings += finding("LOOP-PKG-008", FindingSeverity.WARNING, "string", "Heuristic prompt-risk scan flagged '$where': $cls (model-directed imperative / exfiltration verb+destination / sensitive-path reference / markdown-comment-smuggled instruction).")
                }
            }
        }
        return findings
    }

    private fun finding(code: String, severity: FindingSeverity, objectType: String, message: String, nonDismissible: Boolean? = null): ValidationReportFinding =
        ValidationReportFinding(
            namespace = ValidationNamespace.LOOP_PKG, code = code, severity = severity,
            objectType = objectType, message = message, nonDismissible = nonDismissible,
        )
}
