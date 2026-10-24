package dev.fl1nt.ember.ui.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fl1nt.ember.AppContainer
import dev.fl1nt.ember.R
import dev.fl1nt.ember.api.Expiration
import dev.fl1nt.ember.api.Visibility
import dev.fl1nt.ember.data.account.Account
import dev.fl1nt.ember.data.db.ShortLinkEntity
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.data.db.UploadOrigin
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.data.settings.EmberSettings
import dev.fl1nt.ember.data.settings.LinkFormat
import dev.fl1nt.ember.data.settings.UploadPreset
import dev.fl1nt.ember.data.settings.passwordProblem
import dev.fl1nt.ember.data.uploads.canRetry
import dev.fl1nt.ember.system.Clipboard
import dev.fl1nt.ember.system.SharedText
import dev.fl1nt.ember.system.describeError
import dev.fl1nt.ember.ui.components.FolderChoices
import dev.fl1nt.ember.ui.sync.fetchFolders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SharedFile(val uri: Uri, val name: String, val size: Long?, val mimeType: String?)

enum class SharePhase { LOADING, NOT_CONNECTED, REVIEW_FILES, REVIEW_TEXT, PREPARING, UPLOADING, DONE, FAILED, UNSUPPORTED }

/** What the sheet does with text. */
enum class TextAction { PASTE, SHORTEN }

data class ShareState(
    val phase: SharePhase = SharePhase.LOADING,
    val account: Account? = null,
    val settings: EmberSettings = EmberSettings(),
    val files: List<SharedFile> = emptyList(),
    val rejectedFiles: Int = 0,
    val text: String = "",
    val textMode: ShareRequest.TextMode = ShareRequest.TextMode.SHARE,
    val readOnlySelection: Boolean = true,
    val url: String? = null,
    val textAction: TextAction = TextAction.PASTE,
    val pasteName: String = "",
    val visibility: Visibility? = null,
    val expiration: Expiration? = null,
    val folder: UploadPreset.Choice = UploadPreset.Choice.Inherit,
    val password: String = "",
    val folders: FolderChoices = FolderChoices.Loading,
    val uploads: List<UploadEntity> = emptyList(),
    val shortLink: ShortLinkEntity? = null,
    val copied: Boolean = false,
    val error: String? = null,
) {
    val canShorten: Boolean get() = account?.capabilities?.manageLinks?.isDenied == false
    val passwordError: Int? get() = passwordProblem(password)
    val finished: Int get() = uploads.count { it.status == UploadStatus.DONE }
    val failed: List<UploadEntity> get() = uploads.filter { it.status == UploadStatus.FAILED }

    /** Failed uploads that trying again could fix: Ember still has the file, or the sheet can copy it in again. */
    val retryable: List<UploadEntity> get() = failed.filter { it.canRetry || (it.stagedPath == null && files.isNotEmpty()) }

    /** Whether Try again can do anything: retry uploads, or go back to the form after a failed paste or link. */
    val canRetry: Boolean get() = retryable.isNotEmpty() || (uploads.isEmpty() && (files.isNotEmpty() || text.isNotBlank()))

    /** Links to hand back, in the format chosen in settings. */
    val links: List<String>
        get() = shortLink?.let { listOf(it.shortUrl) }
            ?: uploads.filter { it.status == UploadStatus.DONE }.mapNotNull { if (settings.linkFormat == LinkFormat.RAW_FILE) it.rawUrl ?: it.pageUrl else it.pageUrl }

    /** Whether the link should replace the text someone selected in another app. */
    val returnsToSelection: Boolean
        get() = textMode == ShareRequest.TextMode.SELECTION && !readOnlySelection && settings.replaceSelection
}

class ShareViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state
    private var started = false
    private var watch: Job? = null
    private var request: ShareRequest? = null

    // Set while the sheet's window has focus; cleared after use so it can't leak the activity.
    private var clipboardReader: (() -> String?)? = null

    fun start(request: ShareRequest) {
        if (started) return
        started = true
        this.request = request
        viewModelScope.launch {
            val account = container.accounts.current()
            val settings = container.settings.current()
            _state.update { it.copy(account = account, settings = settings, pasteName = settings.pasteFileName) }
            if (account == null) {
                _state.update { it.copy(phase = SharePhase.NOT_CONNECTED) }
                return@launch
            }
            launch { _state.update { it.copy(folders = fetchFolders(container)) } }
            when (request) {
                is ShareRequest.Files -> loadFiles(request)
                is ShareRequest.Text -> loadText(request.text, request.mode, request.readOnly)
                ShareRequest.Clipboard -> readClipboardIfReady()
                is ShareRequest.Unsupported -> _state.update { it.copy(phase = SharePhase.UNSUPPORTED, rejectedFiles = request.rejectedFiles) }
            }
        }
    }

    /** Called when the sheet's window gains focus, the only time Android allows reading the clipboard. */
    fun onFocused(readClipboard: () -> String?) {
        if (request != ShareRequest.Clipboard) return
        clipboardReader = readClipboard
        readClipboardIfReady()
    }

    private fun readClipboardIfReady() {
        val reader = clipboardReader ?: return
        if (_state.value.phase != SharePhase.LOADING || _state.value.account == null) return
        clipboardReader = null
        val text = reader()
        if (text.isNullOrBlank()) {
            _state.update { it.copy(phase = SharePhase.FAILED, error = container.application.getString(R.string.share_clipboard_empty)) }
        } else {
            loadText(text, ShareRequest.TextMode.CLIPBOARD, readOnly = true)
        }
    }

    private suspend fun loadFiles(request: ShareRequest.Files) {
        val files = withContext(Dispatchers.IO) {
            request.uris.map { uri ->
                val (name, size) = container.staging.describe(uri)
                val mime = runCatching { container.application.contentResolver.getType(uri) }.getOrNull()
                SharedFile(uri, dev.fl1nt.ember.data.uploads.UploadStaging.safeName(name, mime), size, mime)
            }
        }
        _state.update { it.copy(files = files, rejectedFiles = request.rejected, phase = SharePhase.REVIEW_FILES) }
        if (!_state.value.settings.reviewShares) uploadFiles()
    }

    private fun loadText(text: String, mode: ShareRequest.TextMode, readOnly: Boolean) {
        val url = SharedText.singleUrl(text)
        _state.update {
            it.copy(
                phase = SharePhase.REVIEW_TEXT,
                text = text,
                textMode = mode,
                readOnlySelection = readOnly,
                url = url,
                textAction = if (url != null && it.canShorten) TextAction.SHORTEN else TextAction.PASTE,
                pasteName = SharedText.suggestedName(text, it.settings.pasteFileName),
            )
        }
        if (!_state.value.settings.reviewShares && mode == ShareRequest.TextMode.SHARE) submitText()
    }

    fun onTextAction(action: TextAction) = _state.update { it.copy(textAction = action, error = null) }

    fun onPasteName(value: String) = _state.update { it.copy(pasteName = value) }

    fun onVisibility(value: Visibility?) = _state.update { it.copy(visibility = value) }

    fun onExpiration(value: Expiration?) = _state.update { it.copy(expiration = value) }

    fun onFolder(value: UploadPreset.Choice) = _state.update { it.copy(folder = value) }

    fun onPassword(value: String) = _state.update { it.copy(password = value) }

    private fun preset(state: ShareState): UploadPreset =
        UploadPreset(visibility = state.visibility, expiration = state.expiration, folder = state.folder).over(state.settings.defaults)

    fun uploadFiles() {
        val current = _state.value
        if (current.phase != SharePhase.REVIEW_FILES || current.passwordError != null) return
        _state.update { it.copy(phase = SharePhase.PREPARING, error = null) }
        viewModelScope.launch {
            try {
                val batch = container.uploads.enqueueFiles(
                    uris = current.files.map { it.uri },
                    origin = UploadOrigin.SHARE,
                    preset = preset(current),
                    password = current.password.ifEmpty { null },
                    copyLinkWhenSingle = true,
                )
                watchBatch(batch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(phase = SharePhase.FAILED, error = describeError(container.application, error)) }
            }
        }
    }

    fun submitText() {
        val current = _state.value
        if (current.phase != SharePhase.REVIEW_TEXT || current.passwordError != null) return
        if (current.textAction == TextAction.SHORTEN && current.url != null) shorten(current.url) else paste(current)
    }

    private fun paste(current: ShareState) {
        _state.update { it.copy(phase = SharePhase.PREPARING, error = null) }
        viewModelScope.launch {
            try {
                val origin = when (current.textMode) {
                    ShareRequest.TextMode.SHARE -> UploadOrigin.SHARE
                    ShareRequest.TextMode.SELECTION -> UploadOrigin.TEXT_SELECTION
                    ShareRequest.TextMode.CLIPBOARD -> UploadOrigin.CLIPBOARD
                }
                val batch = container.uploads.enqueuePaste(
                    text = current.text,
                    fileName = current.pasteName.trim().ifEmpty { current.settings.pasteFileName },
                    origin = origin,
                    preset = preset(current),
                    password = current.password.ifEmpty { null },
                    // The selected text is replaced instead, so the clipboard stays as it was.
                    copyLink = !current.returnsToSelection,
                )
                watchBatch(batch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(phase = SharePhase.FAILED, error = describeError(container.application, error)) }
            }
        }
    }

    private fun shorten(url: String) {
        _state.update { it.copy(phase = SharePhase.UPLOADING, error = null) }
        viewModelScope.launch {
            try {
                val link = container.links.create(url)
                val copy = _state.value.settings.copyLinkAfterUpload && !_state.value.returnsToSelection
                if (copy) Clipboard.copy(container.application, link.shortUrl, confirm = false)
                _state.update { it.copy(phase = SharePhase.DONE, shortLink = link, copied = copy) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(phase = SharePhase.FAILED, error = describeError(container.application, error)) }
            }
        }
    }

    private fun watchBatch(batch: String) {
        watch?.cancel()
        watch = viewModelScope.launch {
            container.uploads.observeBatch(batch).collect { rows ->
                val active = rows.any { it.status == UploadStatus.QUEUED || it.status == UploadStatus.RUNNING }
                val allFailed = rows.isNotEmpty() && rows.all { it.status == UploadStatus.FAILED }
                val previous = _state.value
                val phase = when {
                    active -> SharePhase.UPLOADING
                    allFailed -> SharePhase.FAILED
                    else -> SharePhase.DONE
                }
                var copied = previous.copied
                if (phase == SharePhase.DONE && previous.phase != SharePhase.DONE) {
                    // A single upload copies its own link in the worker; copy them all for several files.
                    val links = previous.copy(uploads = rows).links
                    if (rows.size > 1 && links.isNotEmpty() && previous.settings.copyLinkAfterUpload) {
                        Clipboard.copy(container.application, links.joinToString("\n"), confirm = false)
                        copied = true
                    } else if (rows.size == 1 && previous.settings.copyLinkAfterUpload && !previous.returnsToSelection) {
                        copied = true
                    }
                }
                _state.update {
                    it.copy(
                        uploads = rows,
                        phase = phase,
                        copied = copied,
                        error = if (allFailed) rows.first().failureMessage ?: container.application.getString(R.string.failure_other) else null,
                    )
                }
            }
        }
    }

    fun retry() {
        val current = _state.value
        viewModelScope.launch {
            // Rows are created in the order the files were shared.
            val ordered = current.uploads.sortedBy { it.createdAt }
            current.retryable.forEach { row ->
                if (row.canRetry) {
                    container.uploads.retry(row.id)
                } else {
                    // It failed while being copied in, so there's no copy to retry from. The sheet can still read the original.
                    val file = current.files.getOrNull(ordered.indexOf(row)) ?: return@forEach
                    container.uploads.restage(row.id, file.uri, current.password.ifEmpty { null })
                }
            }
        }
        if (current.uploads.isEmpty()) {
            _state.update { it.copy(phase = if (it.files.isNotEmpty()) SharePhase.REVIEW_FILES else SharePhase.REVIEW_TEXT, error = null) }
        }
    }
}
