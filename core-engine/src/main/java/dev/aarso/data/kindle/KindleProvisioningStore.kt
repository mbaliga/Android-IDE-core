package dev.aarso.data.kindle

import android.content.Context
import android.net.Uri
import dev.aarso.domain.kindle.KindleFirmwareVersion
import dev.aarso.domain.kindle.KindleProvisioningRun
import dev.aarso.domain.kindle.ProvisioningCheckpoint
import dev.aarso.domain.kindle.ProvisioningStage
import dev.aarso.domain.kindle.ProvisioningStageState
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Durable checkpoints make cable/Wi-Fi loss and process death resumable rather than ambiguous. */
class KindleProvisioningStore(context: Context) {
    private val preferences = context.getSharedPreferences("fonebrew.kindle.provisioning", Context.MODE_PRIVATE)

    @Synchronized fun load(): KindleProvisioningRun? = preferences.getString(KEY, null)?.let(::decode)

    @Synchronized fun save(run: KindleProvisioningRun) {
        check(preferences.edit().putString(KEY, encode(run).toString()).commit()) {
            "Unable to persist Kindle provisioning checkpoint."
        }
    }

    @Synchronized fun clear() {
        preferences.edit().remove(KEY).remove(KEY_HANDLES).remove(KEY_STORAGE).apply()
    }

    @Synchronized fun savePackageHandles(runId: String, handles: Map<String, Uri>) {
        val value = JSONObject().put("runId", runId).put("handles", JSONObject().apply {
            handles.forEach { (id, uri) -> put(id, uri.toString()) }
        })
        check(preferences.edit().putString(KEY_HANDLES, value.toString()).commit())
    }

    @Synchronized fun packageHandles(runId: String): Map<String, Uri> = runCatching {
        val value = JSONObject(preferences.getString(KEY_HANDLES, null) ?: return emptyMap())
        if (value.getString("runId") != runId) return emptyMap()
        val handles = value.getJSONObject("handles")
        handles.keys().asSequence().associateWith { Uri.parse(handles.getString(it)) }
    }.getOrDefault(emptyMap())

    @Synchronized fun saveStorageHandles(runId: String, kindleTree: Uri, backupTree: Uri) {
        preferences.edit().putString(KEY_STORAGE, JSONObject()
            .put("runId", runId).put("kindleTree", kindleTree.toString()).put("backupTree", backupTree.toString())
            .toString()).commit()
    }

    @Synchronized fun storageHandles(runId: String): Pair<Uri, Uri>? = runCatching {
        val value = JSONObject(preferences.getString(KEY_STORAGE, null) ?: return null)
        if (value.getString("runId") != runId) return null
        Uri.parse(value.getString("kindleTree")) to Uri.parse(value.getString("backupTree"))
    }.getOrNull()

    private fun encode(run: KindleProvisioningRun) = JSONObject()
        .put("id", run.id)
        .put("profileId", run.profileId)
        .put("firmware", run.firmware.toString())
        .put("recipeId", run.recipeId)
        .put("recipeVersion", run.recipeVersion)
        .put("checkpoints", JSONArray().apply {
            run.checkpoints.forEach { checkpoint ->
                put(JSONObject()
                    .put("stage", checkpoint.stage.name)
                    .put("state", checkpoint.state.name)
                    .put("updatedAtUtc", checkpoint.updatedAtUtc.toString())
                    .put("detail", checkpoint.detail)
                    .put("receiptDigest", checkpoint.receiptDigest))
            }
        })

    private fun decode(raw: String): KindleProvisioningRun? = runCatching {
        val value = JSONObject(raw)
        val checkpoints = value.getJSONArray("checkpoints")
        KindleProvisioningRun(
            id = value.getString("id"),
            profileId = value.getString("profileId"),
            firmware = KindleFirmwareVersion.parse(value.getString("firmware")),
            recipeId = value.getString("recipeId"),
            recipeVersion = value.getInt("recipeVersion"),
            checkpoints = List(checkpoints.length()) { index ->
                checkpoints.getJSONObject(index).let { checkpoint ->
                    ProvisioningCheckpoint(
                        stage = ProvisioningStage.valueOf(checkpoint.getString("stage")),
                        state = ProvisioningStageState.valueOf(checkpoint.getString("state")),
                        updatedAtUtc = Instant.parse(checkpoint.getString("updatedAtUtc")),
                        detail = checkpoint.optString("detail").takeIf(String::isNotBlank),
                        receiptDigest = checkpoint.optString("receiptDigest").takeIf(String::isNotBlank),
                    )
                }
            },
        )
    }.getOrNull()

    private companion object {
        const val KEY = "active_run"
        const val KEY_HANDLES = "active_package_handles"
        const val KEY_STORAGE = "active_storage_handles"
    }
}
