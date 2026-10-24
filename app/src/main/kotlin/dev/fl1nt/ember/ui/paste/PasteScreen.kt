package dev.fl1nt.ember.ui.paste

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.fl1nt.ember.AppContainer
import dev.fl1nt.ember.R
import dev.fl1nt.ember.api.Expiration
import dev.fl1nt.ember.api.UploadOptions
import dev.fl1nt.ember.api.Visibility
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.data.db.UploadOrigin
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.data.settings.UploadPreset
import dev.fl1nt.ember.system.Clipboard
import dev.fl1nt.ember.system.Links
import dev.fl1nt.ember.system.describeError
import dev.fl1nt.ember.ui.components.EmberTextField
import dev.fl1nt.ember.ui.components.ExpirationRow
import dev.fl1nt.ember.ui.components.FlareCard
import dev.fl1nt.ember.ui.components.GhostButton
import dev.fl1nt.ember.ui.components.IconAction
import dev.fl1nt.ember.ui.components.LinkField
import dev.fl1nt.ember.ui.components.Notice
import dev.fl1nt.ember.ui.components.OutlineButton
import dev.fl1nt.ember.ui.components.PrimaryButton
import dev.fl1nt.ember.ui.components.ScreenHeader
import dev.fl1nt.ember.ui.components.Tone
import dev.fl1nt.ember.ui.components.VisibilityRow
import dev.fl1nt.ember.ui.components.countString
import dev.fl1nt.ember.ui.emberViewModel
import dev.fl1nt.ember.ui.icons.Lucide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PasteState(
    val content: String = "",
    val fileName: String = "",
    val defaultFileName: String = "paste.txt",
    val visibility: Visibility? = null,
    val expiration: Expiration? = null,
    val password: String = "",
    val defaults: UploadPreset = UploadPreset(),
    val upload: UploadEntity? = null,
    val error: String? = null,
) {
    val submitting: Boolean get() = upload != null && upload.status in setOf(UploadStatus.QUEUED, UploadStatus.RUNNING)
    val passwordTooLong: Boolean get() = password.toByteArray(Charsets.UTF_8).size > UploadOptions.MAX_PASSWORD_BYTES
}

class PasteViewModel(private val container: AppContainer, initialText: String = "") : ViewModel() {
    private val _state = MutableStateFlow(PasteState(content = initialText))
    val state: StateFlow<PasteState> = _state
    private var watch: Job? = null

    init {
        viewModelScope.launch {
            val settings = container.settings.current()
            _state.update { it.copy(defaultFileName = settings.pasteFileName, defaults = settings.defaults) }
        }
    }

    fun onContent(value: String) = _state.update { it.copy(content = value, error = null) }

    fun onFileName(value: String) = _state.update { it.copy(fileName = value) }

    fun onVisibility(value: Visibility?) = _state.update { it.copy(visibility = value) }

    fun onExpiration(value: Expiration?) = _state.update { it.copy(expiration = value) }

    fun onPassword(value: String) = _state.update { it.copy(password = value) }

    fun submit() {
        val current = _state.value
        if (current.content.isBlank()) {
            _state.update { it.copy(error = container.application.getString(R.string.paste_error_blank)) }
            return
        }
        if (current.submitting || current.passwordTooLong) return
        viewModelScope.launch {
            val settings = container.settings.current()
            val preset = UploadPreset(visibility = current.visibility, expiration = current.expiration).over(settings.defaults)
            // Saving the paste can fail on a full disk or a secure storage problem. Keep the text and say why.
            val batch = try {
                container.uploads.enqueuePaste(
                    text = current.content,
                    fileName = current.fileName.trim().ifEmpty { current.defaultFileName },
                    origin = UploadOrigin.PASTE,
                    preset = preset,
                    password = current.password.ifEmpty { null },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(error = describeError(container.application, error)) }
                return@launch
            }
            watch?.cancel()
            watch = launch {
                container.uploads.observeBatch(batch).collect { rows ->
                    val row = rows.firstOrNull() ?: return@collect
                    _state.update {
                        it.copy(
                            upload = row,
                            error = if (row.status == UploadStatus.FAILED) row.failureMessage ?: container.application.getString(R.string.failure_other) else null,
                        )
                    }
                }
            }
        }
    }

    fun reset() {
        watch?.cancel()
        _state.update { PasteState(defaultFileName = it.defaultFileName, defaults = it.defaults, visibility = it.visibility, expiration = it.expiration) }
    }
}

@Composable
fun PasteRoute(onBack: () -> Unit, initialText: String = "") {
    val viewModel = emberViewModel(key = "paste") { PasteViewModel(it, initialText) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    PasteScreen(
        state = state,
        onBack = onBack,
        onContent = viewModel::onContent,
        onFileName = viewModel::onFileName,
        onVisibility = viewModel::onVisibility,
        onExpiration = viewModel::onExpiration,
        onPassword = viewModel::onPassword,
        onSubmit = viewModel::submit,
        onAnother = viewModel::reset,
    )
}

@Composable
fun PasteScreen(
    state: PasteState,
    onBack: () -> Unit,
    onContent: (String) -> Unit,
    onFileName: (String) -> Unit,
    onVisibility: (Visibility?) -> Unit,
    onExpiration: (Expiration?) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onAnother: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconAction(Lucide.ArrowLeft, stringResource(R.string.action_back), onBack, tint = MaterialTheme.colorScheme.onSurface)
        ScreenHeader(title = stringResource(R.string.paste_title), subtitle = stringResource(R.string.paste_subtitle))
        val done = state.upload?.takeIf { it.status == UploadStatus.DONE }
        if (done != null) {
            val link = done.pageUrl.orEmpty()
            FlareCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Notice(stringResource(R.string.paste_created, done.fileName), tone = Tone.SUCCESS)
                    LinkField(link, onCopy = { Clipboard.copy(context, link) }, copyLabel = stringResource(R.string.action_copy_link))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(stringResource(R.string.action_share), onClick = { Links.share(context, link) }, icon = Lucide.Share2, modifier = Modifier.weight(1f))
                        OutlineButton(stringResource(R.string.action_open), onClick = { Links.open(context, link) }, icon = Lucide.ExternalLink, modifier = Modifier.weight(1f))
                    }
                    GhostButton(stringResource(R.string.paste_another), onClick = onAnother, icon = Lucide.Plus)
                }
            }
            return@Column
        }
        EmberTextField(
            value = state.content,
            onValueChange = onContent,
            label = stringResource(R.string.paste_content_label),
            placeholder = stringResource(R.string.paste_content_placeholder),
            supportingText = countString(R.plurals.share_characters, state.content.length),
            singleLine = false,
            minLines = 10,
            enabled = !state.submitting,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
        EmberTextField(
            value = state.fileName,
            onValueChange = onFileName,
            label = stringResource(R.string.paste_file_label),
            placeholder = state.defaultFileName,
            supportingText = stringResource(R.string.paste_file_help),
            enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        FlareCard(contentPadding = PaddingValues(vertical = 4.dp)) {
            VisibilityRow(state.visibility, onVisibility, state.defaults)
            ExpirationRow(state.expiration, onExpiration, state.defaults)
        }
        EmberTextField(
            value = state.password,
            onValueChange = onPassword,
            label = stringResource(R.string.field_password),
            placeholder = stringResource(R.string.field_password_placeholder),
            supportingText = stringResource(R.string.field_password_help),
            errorText = if (state.passwordTooLong) stringResource(R.string.field_password_too_long) else null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            leadingIcon = Lucide.Lock,
            enabled = !state.submitting,
        )
        state.error?.let { Notice(it, tone = Tone.DANGER) }
        PrimaryButton(
            stringResource(if (state.submitting) R.string.paste_creating else R.string.paste_create),
            onClick = onSubmit,
            loading = state.submitting,
            enabled = state.content.isNotBlank() && !state.passwordTooLong,
            icon = Lucide.Send,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.paste_review_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}
