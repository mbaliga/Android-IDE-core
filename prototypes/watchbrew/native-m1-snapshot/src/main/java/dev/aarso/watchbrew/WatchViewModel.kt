// SPDX-License-Identifier: Apache-2.0
package dev.aarso.watchbrew

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WatchUiState(val session: Session = Session(), val loaded: Boolean = false, val storageError: String? = null, val readOnly: Boolean = false)

class WatchViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(application.filesDir)
    private val reducer = SessionReducer()
    private val events = Channel<Action>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(WatchUiState())
    val ui = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            try { mutable.value = WatchUiState(withContext(Dispatchers.IO) { store.load() }, loaded = true) }
            catch (_: Exception) {
                // Preserve the unreadable file. Never silently replace a queued draft with a fresh session.
                mutable.value = WatchUiState(loaded = true, readOnly = true, storageError = "Saved session could not be read. Changes are disabled to preserve it.")
                return@launch
            }
            for (event in events) {
                val next = reducer.reduce(mutable.value.session, event)
                try {
                    withContext(Dispatchers.IO) { store.save(next) }
                    mutable.value = WatchUiState(next, loaded = true)
                } catch (_: Exception) {
                    mutable.value = mutable.value.copy(readOnly = true, storageError = "Could not save this change. Changes are disabled; previous saved state is retained. Reopen after storage is available.")
                    return@launch
                }
            }
        }
    }
    fun dispatch(action: Action) { if (mutable.value.loaded && !mutable.value.readOnly) events.trySend(action) }
    override fun onCleared() { events.close(); super.onCleared() }
}
