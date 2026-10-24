package dev.fl1nt.ember.ui.sync

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fl1nt.ember.AppContainer
import dev.fl1nt.ember.api.FlareException
import dev.fl1nt.ember.data.db.LedgerCounts
import dev.fl1nt.ember.data.db.SourceType
import dev.fl1nt.ember.data.db.SyncSourceEntity
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.data.settings.SyncPreferences
import dev.fl1nt.ember.data.settings.UploadPreset
import dev.fl1nt.ember.data.sync.MediaAlbum
import dev.fl1nt.ember.data.sync.NewSource
import dev.fl1nt.ember.data.sync.SyncProgress
import dev.fl1nt.ember.system.MediaAccess
import dev.fl1nt.ember.system.MediaPermissions
import dev.fl1nt.ember.system.describeError
import dev.fl1nt.ember.ui.attempt
import dev.fl1nt.ember.ui.components.FolderChoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SourceSummary(val source: SyncSourceEntity, val counts: LedgerCounts)

data class SyncState(
    val sources: List<SourceSummary> = emptyList(),
    val prefs: SyncPreferences = SyncPreferences(),
    val running: Boolean = false,
    val progress: SyncProgress? = null,
    val mediaAccess: MediaAccess = MediaAccess.FULL,
    val loaded: Boolean = false,
    val needsLocalNetwork: Boolean = false,
) {
    val needsAttention: Boolean get() = needsLocalNetwork || sources.any { it.source.enabled && it.source.lastError != null }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModel(private val container: AppContainer) : ViewModel() {

    private val mediaAccess = MutableStateFlow(MediaPermissions.access(container.application))
    private val localNetworkBlocked = MutableStateFlow(false)

    private val summaries: Flow<List<SourceSummary>> = container.sync.observeSources().flatMapLatest { sources ->
        if (sources.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(sources.map { source -> container.sync.observeCounts(source.id) }) { counts ->
                sources.mapIndexed { index, source -> SourceSummary(source, counts[index]) }
            }
        }
    }

    val state: StateFlow<SyncState> = combine(
        summaries,
        container.settings.settings,
        container.scheduler.observeSyncRunning(),
        container.syncEngine.progress,
        combine(mediaAccess, localNetworkBlocked) { access, blocked -> access to blocked },
    ) { sources, settings, running, progress, (access, blocked) ->
        SyncState(sources, settings.sync, running || progress != null, progress, access, loaded = true, needsLocalNetwork = blocked)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncState())

    fun refreshPermissions() {
        mediaAccess.value = MediaPermissions.access(container.application)
        viewModelScope.launch {
            val account = container.accounts.current()
            localNetworkBlocked.value = account != null && container.localNetwork.needsPermission(account.origin)
        }
    }

    /** Local network access was just granted, so sync what waited for it. */
    fun onLocalNetworkGranted() {
        localNetworkBlocked.value = false
        viewModelScope.launch {
            container.scheduler.resumeUploads(container.database.uploads().queuedCount())
            container.sync.reschedule(force = false)
            // Not user-initiated, so a sync that's switched off stays off.
            container.scheduler.syncNow(userInitiated = false)
        }
    }

    fun setSyncEnabled(enabled: Boolean) = viewModelScope.launch {
        container.settings.update { it.copy(sync = it.sync.copy(enabled = enabled)) }
        container.sync.reschedule(force = true)
    }

    fun setSourceEnabled(id: Long, enabled: Boolean) = viewModelScope.launch { container.sync.setEnabled(id, enabled) }

    fun syncNow() = viewModelScope.launch { container.scheduler.syncNow(userInitiated = true) }
}

/** Options for a sync folder, shared by "add" and "edit". */
data class SourceOptions(
    val uploadExisting: Boolean = false,
    val preset: UploadPreset = UploadPreset(),
    val copyLink: Boolean = false,
    val notifyEach: Boolean = false,
    val includeImages: Boolean = true,
    val includeVideos: Boolean = true,
    val includeSubfolders: Boolean = true,
)

data class AddSourceState(
    val albums: List<MediaAlbum> = emptyList(),
    val albumsLoading: Boolean = true,
    val mediaAccess: MediaAccess = MediaAccess.NONE,
    val selected: NewSource? = null,
    val options: SourceOptions = SourceOptions(),
    val folders: FolderChoices = FolderChoices.Loading,
    val defaults: UploadPreset = UploadPreset(),
    val saving: Boolean = false,
    val error: String? = null,
    val albumsError: String? = null,
)

class AddSourceViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(AddSourceState())
    val state: StateFlow<AddSourceState> = _state

    init {
        refresh()
        loadFolders()
    }

    fun refresh() {
        val access = MediaPermissions.access(container.application)
        _state.update { it.copy(mediaAccess = access, albumsLoading = access != MediaAccess.NONE) }
        if (access == MediaAccess.NONE) return
        viewModelScope.launch {
            attempt { container.sync.listAlbums() }
                .onSuccess { albums -> _state.update { it.copy(albums = albums, albumsLoading = false, albumsError = null) } }
                .onFailure { error -> _state.update { it.copy(albums = emptyList(), albumsLoading = false, albumsError = describeError(container.application, error)) } }
        }
    }

    private fun loadFolders() = viewModelScope.launch {
        val defaults = container.settings.current().defaults
        _state.update { it.copy(defaults = defaults, folders = fetchFolders(container)) }
    }

    fun pickAlbum(album: MediaAlbum) = _state.update {
        it.copy(
            selected = NewSource.Album(album),
            // A screenshot tool should hand you the link right away.
            options = it.options.copy(copyLink = album.isScreenshots, notifyEach = album.isScreenshots),
            error = null,
        )
    }

    fun pickFolder(uri: Uri) = viewModelScope.launch {
        try {
            val folder = container.sync.describeFolder(uri)
            _state.update { it.copy(selected = folder, options = it.options.copy(copyLink = false, notifyEach = false), error = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(error = describeError(container.application, error)) }
        }
    }

    fun clearSelection() = _state.update { it.copy(selected = null) }

    fun updateOptions(transform: (SourceOptions) -> SourceOptions) = _state.update { it.copy(options = transform(it.options)) }

    fun save(onSaved: (Long) -> Unit) {
        val current = _state.value
        val source = current.selected ?: return
        if (current.saving) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val options = current.options
                val id = container.sync.add(source, options.uploadExisting, options.preset, options.copyLink, options.notifyEach)
                if (!options.includeImages || !options.includeVideos || !options.includeSubfolders) {
                    container.database.sources().get(id)?.let { saved ->
                        container.sync.update(
                            saved.copy(includeImages = options.includeImages, includeVideos = options.includeVideos, includeSubfolders = options.includeSubfolders),
                        )
                    }
                }
                onSaved(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(saving = false, error = describeError(container.application, error)) }
            }
        }
    }
}

data class SourceDetailState(
    val source: SyncSourceEntity? = null,
    val counts: LedgerCounts = LedgerCounts(0, 0),
    val recent: List<UploadEntity> = emptyList(),
    val folders: FolderChoices = FolderChoices.Loading,
    val removed: Boolean = false,
    val defaults: UploadPreset = UploadPreset(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class SourceDetailViewModel(private val container: AppContainer, private val id: Long) : ViewModel() {

    private val folders = MutableStateFlow<FolderChoices>(FolderChoices.Loading)
    private val removed = MutableStateFlow(false)

    val state: StateFlow<SourceDetailState> = combine(
        container.sync.observeSource(id),
        container.sync.observeCounts(id),
        container.sync.observeRecentUploads(id),
        combine(folders, removed, container.settings.settings) { folders, removed, settings -> Triple(folders, removed, settings.defaults) },
    ) { source, counts, recent, (folders, removed, defaults) -> SourceDetailState(source, counts, recent, folders, removed, defaults) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceDetailState())

    init {
        viewModelScope.launch { folders.value = fetchFolders(container) }
    }

    fun update(transform: (SyncSourceEntity) -> SyncSourceEntity) = viewModelScope.launch {
        val source = container.database.sources().get(id) ?: return@launch
        container.sync.update(transform(source))
    }

    fun updatePreset(transform: (UploadPreset) -> UploadPreset) = update { it.copy(presetJson = transform(UploadPreset.decode(it.presetJson)).encode()) }

    fun retryFailures() = viewModelScope.launch { container.sync.retryFailures(id) }

    fun remove() = viewModelScope.launch {
        container.sync.remove(id)
        removed.value = true
    }

    val isAlbum: Boolean get() = state.value.source?.type == SourceType.MEDIA_ALBUM
}

/** Folders for pickers, or why there are none. */
suspend fun fetchFolders(container: AppContainer): FolderChoices {
    val account = container.accounts.current() ?: return FolderChoices.NotPermitted
    if (account.capabilities.listFolders.isDenied) return FolderChoices.NotPermitted
    return attempt {
        withContext(Dispatchers.IO) { container.connection.client()?.listFolders() ?: throw FlareException.Unauthorized() }
    }.fold(
        onSuccess = { FolderChoices.Loaded(it) },
        onFailure = { error ->
            // An unknown answer at connect time can turn out to mean "not allowed".
            if (error is FlareException.Unauthorized && !account.capabilities.listFolders.isGranted) {
                FolderChoices.NotPermitted
            } else {
                FolderChoices.Failed(describeError(container.application, error, account.origin.displayName))
            }
        },
    )
}
