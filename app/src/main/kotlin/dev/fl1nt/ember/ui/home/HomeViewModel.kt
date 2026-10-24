package dev.fl1nt.ember.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fl1nt.ember.AppContainer
import dev.fl1nt.ember.data.account.Account
import dev.fl1nt.ember.data.db.SyncSourceEntity
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.data.db.UploadOrigin
import dev.fl1nt.ember.data.sync.SyncProgress
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeState(
    val account: Account? = null,
    val recent: List<UploadEntity> = emptyList(),
    val activeUploads: Int = 0,
    val sources: List<SyncSourceEntity> = emptyList(),
    val syncEnabled: Boolean = true,
    val syncRunning: Boolean = false,
    val syncProgress: SyncProgress? = null,
    val needsLocalNetwork: Boolean = false,
    /** False until uploads and folders have loaded, so "nothing uploaded yet" doesn't flash first. */
    val loaded: Boolean = false,
) {
    val enabledSources: Int get() = sources.count { it.enabled }
    val pending: Int get() = sources.filter { it.enabled }.sumOf { it.pendingCount }
    val lastSyncAt: Long? get() = sources.mapNotNull { it.lastScanAt }.maxOrNull()
    val syncNeedsAttention: Boolean get() = needsLocalNetwork || sources.any { it.enabled && it.lastError != null }
}

sealed interface HomeEvent {
    data class Queued(val count: Int) : HomeEvent

    data object SyncStarted : HomeEvent
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<HomeEvent> = _events

    private val uploadsFlow = combine(container.uploads.observeRecent(5), container.uploads.observeActiveCount()) { recent, active -> recent to active }
    private val syncFlow = combine(
        container.sync.observeSources(),
        container.settings.settings,
        container.scheduler.observeSyncRunning(),
        container.syncEngine.progress,
    ) { sources, settings, running, progress -> SyncSnapshot(sources, settings.sync.enabled, running, progress) }

    private val localNetworkBlocked = MutableStateFlow(false)

    val state: StateFlow<HomeState> = combine(container.accounts.account, uploadsFlow, syncFlow, localNetworkBlocked) { account, (recent, active), sync, blocked ->
        HomeState(
            account = account,
            recent = recent,
            activeUploads = active,
            sources = sync.sources,
            syncEnabled = sync.enabled,
            syncRunning = sync.running || sync.progress != null,
            syncProgress = sync.progress,
            needsLocalNetwork = blocked,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    /** Checks again whether Ember needs local network access, such as after a visit to system settings. */
    fun refreshAccess() {
        viewModelScope.launch {
            val account = container.accounts.current()
            localNetworkBlocked.value = account != null && container.localNetwork.needsPermission(account.origin)
        }
    }

    /** Access was just granted, so continue whatever was waiting for it. */
    fun onLocalNetworkGranted() {
        viewModelScope.launch {
            localNetworkBlocked.value = false
            container.scheduler.resumeUploads(container.database.uploads().queuedCount())
            container.sync.reschedule(force = false)
            if (container.database.sources().all().any { it.enabled }) container.scheduler.syncNow(userInitiated = false)
        }
    }

    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val defaults = container.settings.current().defaults
            container.uploads.enqueueFiles(uris, UploadOrigin.PICKER, defaults)
            _events.emit(HomeEvent.Queued(uris.size))
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            container.scheduler.syncNow(userInitiated = true)
            _events.emit(HomeEvent.SyncStarted)
        }
    }

    private data class SyncSnapshot(
        val sources: List<SyncSourceEntity>,
        val enabled: Boolean,
        val running: Boolean,
        val progress: SyncProgress?,
    )
}
