package dev.fl1nt.ember.ui.links

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.fl1nt.ember.AppContainer
import dev.fl1nt.ember.R
import dev.fl1nt.ember.data.db.ShortLinkEntity
import dev.fl1nt.ember.system.Clipboard
import dev.fl1nt.ember.system.Links
import dev.fl1nt.ember.system.SharedText
import dev.fl1nt.ember.system.describeError
import dev.fl1nt.ember.ui.components.EmberTextField
import dev.fl1nt.ember.ui.components.FlareCard
import dev.fl1nt.ember.ui.components.GhostButton
import dev.fl1nt.ember.ui.components.IconAction
import dev.fl1nt.ember.ui.components.LinkField
import dev.fl1nt.ember.ui.components.Notice
import dev.fl1nt.ember.ui.components.OutlineButton
import dev.fl1nt.ember.ui.components.PrimaryButton
import dev.fl1nt.ember.ui.components.ScreenHeader
import dev.fl1nt.ember.ui.components.Tone
import dev.fl1nt.ember.ui.emberViewModel
import dev.fl1nt.ember.ui.icons.Lucide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShortenState(
    val url: String = "",
    val submitting: Boolean = false,
    val result: ShortLinkEntity? = null,
    val copied: Boolean = false,
    val error: String? = null,
)

class ShortenViewModel(private val container: AppContainer, initialUrl: String = "") : ViewModel() {
    private val _state = MutableStateFlow(ShortenState(url = initialUrl))
    val state: StateFlow<ShortenState> = _state

    fun onUrl(value: String) = _state.update { it.copy(url = value, error = null) }

    fun submit() {
        val current = _state.value
        val target = SharedText.singleUrl(current.url)
        if (target == null) {
            _state.update { it.copy(error = container.application.getString(R.string.shorten_error_url)) }
            return
        }
        if (current.submitting) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val link = container.links.create(target)
                val copy = container.settings.current().copyLinkAfterUpload
                if (copy) Clipboard.copy(container.application, link.shortUrl, confirm = false)
                _state.update { it.copy(submitting = false, result = link, copied = copy) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(submitting = false, error = describeError(container.application, error)) }
            }
        }
    }

    fun reset() = _state.update { ShortenState() }
}

@Composable
fun ShortenRoute(onBack: () -> Unit, initialUrl: String = "") {
    val viewModel = emberViewModel(key = "shorten") { ShortenViewModel(it, initialUrl) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ShortenScreen(
        state = state,
        onBack = onBack,
        onUrl = viewModel::onUrl,
        onPasteUrl = { Clipboard.readText(context)?.let(viewModel::onUrl) },
        onSubmit = viewModel::submit,
        onAnother = viewModel::reset,
    )
}

@Composable
fun ShortenScreen(
    state: ShortenState,
    onBack: () -> Unit,
    onUrl: (String) -> Unit,
    onPasteUrl: () -> Unit,
    onSubmit: () -> Unit,
    onAnother: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconAction(Lucide.ArrowLeft, stringResource(R.string.action_back), onBack, tint = MaterialTheme.colorScheme.onSurface)
        ScreenHeader(title = stringResource(R.string.shorten_title), subtitle = stringResource(R.string.shorten_subtitle))
        val result = state.result
        if (result != null) {
            FlareCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Notice(stringResource(if (state.copied) R.string.shorten_done_copied else R.string.shorten_done), tone = Tone.SUCCESS)
                    LinkField(result.shortUrl, onCopy = { Clipboard.copy(context, result.shortUrl) }, copyLabel = stringResource(R.string.action_copy_link))
                    Text(stringResource(R.string.links_points_to, result.targetUrl), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(stringResource(R.string.action_share), onClick = { Links.share(context, result.shortUrl) }, icon = Lucide.Share2, modifier = Modifier.weight(1f))
                        OutlineButton(stringResource(R.string.action_open), onClick = { Links.open(context, result.shortUrl) }, icon = Lucide.ExternalLink, modifier = Modifier.weight(1f))
                    }
                    GhostButton(stringResource(R.string.shorten_another), onClick = onAnother, icon = Lucide.Plus)
                }
            }
            return@Column
        }
        EmberTextField(
            value = state.url,
            onValueChange = onUrl,
            label = stringResource(R.string.shorten_url_label),
            placeholder = "https://",
            supportingText = stringResource(R.string.shorten_url_help),
            enabled = !state.submitting,
            leadingIcon = Lucide.Link,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            trailing = { IconAction(Lucide.ClipboardPaste, stringResource(R.string.action_paste), onPasteUrl) },
        )
        state.error?.let { Notice(it, tone = Tone.DANGER) }
        PrimaryButton(
            stringResource(if (state.submitting) R.string.shorten_creating else R.string.shorten_create),
            onClick = onSubmit,
            loading = state.submitting,
            enabled = state.url.isNotBlank(),
            icon = Lucide.Zap,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
