package dev.aarso.domain.kindle

import org.json.JSONArray
import org.json.JSONObject

enum class ProvisioningInstructionKind { COPY, EXTRACT_PACKAGE, USER_ACTION, RESTART, VERIFY }

data class ProvisioningInstruction(
    val id: String,
    val kind: ProvisioningInstructionKind,
    val title: String,
    val detail: String,
    val sourcePackageId: String? = null,
    val destinationRelativePath: String? = null,
    val expectedState: String? = null,
) {
    init {
        require(id.isNotBlank() && title.isNotBlank() && detail.isNotBlank()) { "Provisioning instruction is incomplete." }
        if (kind == ProvisioningInstructionKind.COPY) {
            require(!sourcePackageId.isNullOrBlank()) { "COPY instruction requires sourcePackageId." }
            require(isSafeRelativePath(destinationRelativePath)) { "COPY destination is unsafe." }
        }
        if (kind == ProvisioningInstructionKind.EXTRACT_PACKAGE) {
            require(!sourcePackageId.isNullOrBlank()) { "EXTRACT_PACKAGE instruction requires sourcePackageId." }
            require(destinationRelativePath == null || isSafeRelativePath(destinationRelativePath)) {
                "EXTRACT_PACKAGE destination is unsafe."
            }
        }
        if (kind == ProvisioningInstructionKind.VERIFY) {
            require(!expectedState.isNullOrBlank()) { "VERIFY instruction requires expectedState." }
        }
    }
}

data class ProvisioningPackage(
    val id: String,
    val version: String,
    val sourceUrl: String,
    val sha256: String,
    val sizeBytes: Long?,
) {
    init {
        require(id.isNotBlank() && version.isNotBlank()) { "Provisioning package identity is incomplete." }
        require(sourceUrl.startsWith("https://")) { "Provisioning packages require HTTPS." }
        require(SHA256.matches(sha256)) { "Provisioning package has an invalid SHA-256." }
        require(sizeBytes == null || sizeBytes > 0) { "Package size must be positive when present." }
    }

    companion object { val SHA256 = Regex("^[a-fA-F0-9]{64}$") }
}

data class KindleProvisioningRecipe(
    val id: String,
    val version: Int,
    val supportedProfileIds: Set<String>,
    val supportedFirmware: List<KindleFirmwareRange>,
    val packages: List<ProvisioningPackage>,
    val instructions: List<ProvisioningInstruction>,
    val expectedResultingStates: Set<String>,
) {
    init {
        require(id.isNotBlank() && version > 0) { "Provisioning recipe identity is invalid." }
        require(supportedProfileIds.isNotEmpty() && supportedFirmware.isNotEmpty()) { "Recipe support bounds are required." }
        require(instructions.isNotEmpty()) { "Provisioning recipe must contain instructions." }
        val packageIds = packages.map { it.id }
        require(packageIds.distinct().size == packageIds.size) { "Duplicate package id in recipe." }
        val known = packageIds.toSet()
        require(instructions.filter { it.kind in setOf(ProvisioningInstructionKind.COPY, ProvisioningInstructionKind.EXTRACT_PACKAGE) }
            .all { it.sourcePackageId in known }) {
            "Package instruction references an unknown package."
        }
        require(expectedResultingStates.isNotEmpty()) { "Recipe must define its expected resulting state." }
    }
}

data class KindleProvisioningManifest(
    val schemaVersion: Int,
    val manifestVersion: Int,
    val generatedAtUtc: String,
    val recipes: List<KindleProvisioningRecipe>,
) {
    init {
        require(schemaVersion == 1) { "Unsupported Kindle provisioning manifest schema: $schemaVersion" }
        require(manifestVersion > 0 && generatedAtUtc.isNotBlank()) { "Manifest metadata is incomplete." }
        require(recipes.map { it.id to it.version }.distinct().size == recipes.size) { "Duplicate recipe id/version." }
    }
}

sealed interface ProvisioningSelection {
    data class Approved(val recipe: KindleProvisioningRecipe) : ProvisioningSelection
    data class Blocked(val reason: String) : ProvisioningSelection
}

object KindleProvisioningPolicy {
    fun select(
        manifest: KindleProvisioningManifest,
        profile: KindleDeviceProfile?,
        firmware: KindleFirmwareVersion?,
    ): ProvisioningSelection {
        if (profile == null) return ProvisioningSelection.Blocked("Kindle model is not identified.")
        if (firmware == null) return ProvisioningSelection.Blocked("Kindle firmware is not identified.")
        val compatible = manifest.recipes.filter { recipe ->
            profile.id in recipe.supportedProfileIds && recipe.supportedFirmware.any { firmware in it }
        }.sortedWith(compareByDescending<KindleProvisioningRecipe> { it.version }.thenBy { it.id })
        if (compatible.isEmpty()) {
            return ProvisioningSelection.Blocked(
                "No approved manifest recipe supports ${profile.model} firmware $firmware. Import an updated manifest; do not guess.",
            )
        }
        return ProvisioningSelection.Approved(compatible.first())
    }
}

object KindleProvisioningManifestCodec {
    private val ROOT_KEYS = setOf("schemaVersion", "manifestVersion", "generatedAtUtc", "recipes")
    private val RECIPE_KEYS = setOf(
        "id", "version", "supportedProfileIds", "supportedFirmware", "packages", "instructions", "expectedResultingStates",
    )
    private val RANGE_KEYS = setOf("minimumInclusive", "maximumInclusive")
    private val PACKAGE_KEYS = setOf("id", "version", "sourceUrl", "sha256", "sizeBytes")
    private val INSTRUCTION_KEYS = setOf(
        "id", "kind", "title", "detail", "sourcePackageId", "destinationRelativePath", "expectedState",
    )

    fun decode(raw: String): KindleProvisioningManifest {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "Manifest exceeds size limit." }
        val root = JSONObject(raw)
        requireExactKeys(root, ROOT_KEYS, "manifest")
        return KindleProvisioningManifest(
            schemaVersion = root.getInt("schemaVersion"),
            manifestVersion = root.getInt("manifestVersion"),
            generatedAtUtc = root.getString("generatedAtUtc"),
            recipes = root.getJSONArray("recipes").mapObjects(::decodeRecipe),
        )
    }

    private fun decodeRecipe(value: JSONObject): KindleProvisioningRecipe {
        requireExactKeys(value, RECIPE_KEYS, "recipe")
        return KindleProvisioningRecipe(
            id = value.getString("id"),
            version = value.getInt("version"),
            supportedProfileIds = value.getJSONArray("supportedProfileIds").mapStrings().toSet(),
            supportedFirmware = value.getJSONArray("supportedFirmware").mapObjects { range ->
                requireExactKeys(range, RANGE_KEYS, "firmware range")
                KindleFirmwareRange(
                    KindleFirmwareVersion.parse(range.getString("minimumInclusive")),
                    KindleFirmwareVersion.parse(range.getString("maximumInclusive")),
                )
            },
            packages = value.getJSONArray("packages").mapObjects { item ->
                requireExactKeys(item, PACKAGE_KEYS, "package", optional = setOf("sizeBytes"))
                ProvisioningPackage(
                    id = item.getString("id"),
                    version = item.getString("version"),
                    sourceUrl = item.getString("sourceUrl"),
                    sha256 = item.getString("sha256").lowercase(),
                    sizeBytes = if (item.has("sizeBytes") && !item.isNull("sizeBytes")) item.getLong("sizeBytes") else null,
                )
            },
            instructions = value.getJSONArray("instructions").mapObjects { item ->
                requireExactKeys(
                    item,
                    INSTRUCTION_KEYS,
                    "instruction",
                    optional = setOf("sourcePackageId", "destinationRelativePath", "expectedState"),
                )
                ProvisioningInstruction(
                    id = item.getString("id"),
                    kind = ProvisioningInstructionKind.valueOf(item.getString("kind")),
                    title = item.getString("title"),
                    detail = item.getString("detail"),
                    sourcePackageId = item.optString("sourcePackageId").takeIf(String::isNotBlank),
                    destinationRelativePath = item.optString("destinationRelativePath").takeIf(String::isNotBlank),
                    expectedState = item.optString("expectedState").takeIf(String::isNotBlank),
                )
            },
            expectedResultingStates = value.getJSONArray("expectedResultingStates").mapStrings().toSet(),
        )
    }

    private fun requireExactKeys(value: JSONObject, allowed: Set<String>, label: String, optional: Set<String> = emptySet()) {
        val actual = value.keys().asSequence().toSet()
        require((allowed - optional).all(actual::contains)) { "$label is missing required keys." }
        require(actual.all(allowed::contains)) { "$label contains unsupported keys: ${actual - allowed}" }
    }

    private fun JSONArray.mapStrings(): List<String> = List(length()) { getString(it) }
    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        List(length()) { transform(getJSONObject(it)) }

    private const val MAX_MANIFEST_BYTES = 512 * 1024
}

private fun isSafeRelativePath(path: String?): Boolean {
    if (path.isNullOrBlank() || path.startsWith('/') || '\u0000' in path) return false
    val segments = path.replace('\\', '/').split('/')
    return segments.none { it.isBlank() || it == "." || it == ".." }
}
