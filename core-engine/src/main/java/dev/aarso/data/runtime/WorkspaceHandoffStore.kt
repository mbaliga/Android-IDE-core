package dev.aarso.data.runtime

import android.content.Context
import android.net.Uri
import dev.aarso.domain.runtime.FylzWorkspaceRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WorkspaceHandoffStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("workspace_handoff", Context.MODE_PRIVATE)

    private val _active = MutableStateFlow(loadPersisted())
    val active: StateFlow<FylzWorkspaceRef?> = _active.asStateFlow()

    fun set(ref: FylzWorkspaceRef) {
        prefs.edit()
            .putString(KEY_URI, ref.treeUri)
            .putString(KEY_NAME, ref.displayName)
            .putBoolean(KEY_READ_ONLY, ref.readOnly)
            .apply()
        _active.value = ref
    }

    fun clear() {
        prefs.edit().clear().apply()
        _active.value = null
    }

    private fun loadPersisted(): FylzWorkspaceRef? {
        val value = prefs.getString(KEY_URI, null) ?: return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val stillGranted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!stillGranted) return null
        return FylzWorkspaceRef(
            treeUri = value,
            displayName = prefs.getString(KEY_NAME, null),
            readOnly = prefs.getBoolean(KEY_READ_ONLY, false),
        )
    }

    companion object {
        private const val KEY_URI = "tree_uri"
        private const val KEY_NAME = "display_name"
        private const val KEY_READ_ONLY = "read_only"
    }
}
