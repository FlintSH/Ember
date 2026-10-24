package dev.fl1nt.ember.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fl1nt.ember.AppContainer
import dev.fl1nt.ember.api.FileQuery
import dev.fl1nt.ember.api.FlareException
import dev.fl1nt.ember.api.RemoteFile
import dev.fl1nt.ember.data.account.Account
import dev.fl1nt.ember.data.db.ShortLinkEntity
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.system.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryTab { RECENT, FILES, LINKS }

data class RemoteFiles(
    val query: String = "",
    val items: List<RemoteFile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val page: Int = 0,
    val endReached: Boolean = false,
)

data class LinksState(val refreshing: Boolean = false, val error: String? = null, val fromServer: Boolean = false)

data class LibraryState(
    val tab: LibraryTab = LibraryTab.RECENT,
    val account: Account? = null,
    val recent: List<UploadEntity> = emptyList(),
    val files: RemoteFiles = RemoteFiles(),
    val links: List<ShortLinkEntity> = emptyList(),
    val linksState: LinksState = LinksState(),
)

class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    private val tab = MutableStateFlow(LibraryTab.RECENT)
    private val files = MutableStateFlow(RemoteFiles())
    private val linksState = MutableStateFlow(LinksState())
    private var searchJob: Job? = null
    private var pageJob: Job? = null

    val state: StateFlow<LibraryState> = combine(
        combine(tab, container.accounts.account) { tab, account -> tab to account },
        container.uploads.observeRecent(),
        files,
        container.links.observe(),
        linksState,
    ) { (tab, account), recent, files, links, linksState ->
        LibraryState(tab, account, recent, files, links, linksState)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    fun select(next: LibraryTab) {
        tab.value = next
        when (next) {
            LibraryTab.FILES -> if (files.value.page == 0 && !files.value.loading) refreshFiles()
            LibraryTab.LINKS -> refreshLinks()
            LibraryTab.RECENT -> Unit
        }
    }

    fun search(query: String) {
        files.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            refreshFiles()
        }
    }

    fun refreshFiles() {
        pageJob?.cancel()
        // The cancelled page can't clear its own spinner, so start clean.
        files.update { it.copy(items = emptyList(), page = 0, endReached = false, loading = false, error = null) }
        loadMore()
    }

    fun loadMore() {
        val current = files.value
        if (current.loading || current.endReached) return
        files.update { it.copy(loading = true, error = null) }
        pageJob = viewModelScope.launch {
            try {
                val client = container.connection.client() ?: throw FlareException.Unauthorized()
                val page = client.listFiles(FileQuery(page = current.page + 1, limit = PAGE_SIZE, search = current.query.ifBlank { null }))
                files.update {
                    it.copy(
                        items = it.items + page.files,
                        page = page.pagination.page,
                        endReached = page.pagination.page >= page.pagination.pageCount || page.files.isEmpty(),
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val host = container.accounts.account.first()?.origin?.displayName
                files.update { it.copy(loading = false, error = describeError(container.application, error, host)) }
            }
        }
    }

    fun refreshLinks() {
        viewModelScope.launch {
            linksState.update { it.copy(refreshing = true, error = null) }
            try {
                val fromServer = container.links.refresh()
                linksState.value = LinksState(refreshing = false, fromServer = fromServer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                linksState.value = LinksState(refreshing = false, error = describeError(container.application, error))
            }
        }
    }

    fun deleteLink(id: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                container.links.delete(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onError(describeError(container.application, error))
            }
        }
    }

    fun retry(id: String) = viewModelScope.launch { container.uploads.retry(id) }

    fun cancel(id: String) = viewModelScope.launch { container.uploads.cancel(id) }

    fun remove(id: String) = viewModelScope.launch { container.uploads.remove(id) }

    fun clearFinished() = viewModelScope.launch { container.uploads.clearFinished() }

    private companion object {
        const val PAGE_SIZE = 30
        const val SEARCH_DEBOUNCE_MILLIS = 350L
    }
}
