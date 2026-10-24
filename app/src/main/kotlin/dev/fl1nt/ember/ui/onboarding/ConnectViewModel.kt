package dev.fl1nt.ember.ui.onboarding

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fl1nt.ember.AppContainer
import dev.fl1nt.ember.R
import dev.fl1nt.ember.api.Access
import dev.fl1nt.ember.api.Capabilities
import dev.fl1nt.ember.api.FlareCredential
import dev.fl1nt.ember.api.FlareOrigin
import dev.fl1nt.ember.api.InvalidOriginException
import dev.fl1nt.ember.system.LocalAddresses
import dev.fl1nt.ember.system.describeError
import dev.fl1nt.ember.work.Attention
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectState(
    val address: String = "",
    val token: String = "",
    val tokenVisible: Boolean = false,
    val step: ConnectStep = ConnectStep.Editing,
    val addressError: String? = null,
    val tokenError: String? = null,
    val error: String? = null,
)

sealed interface ConnectStep {
    data object Editing : ConnectStep

    data object Checking : ConnectStep

    /** Plain HTTP to a non-local address would expose the token. Ask first. */
    data class ConfirmHttp(val host: String) : ConnectStep

    /** Android 17 needs permission to reach a server on the local network. */
    data class NeedsLocalNetwork(val host: String) : ConnectStep

    data class Connected(val capabilities: Capabilities) : ConnectStep
}

class ConnectViewModel(
    private val container: AppContainer,
    private val app: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    private var httpConfirmedFor: String? = null

    init {
        // After a disconnect, for example to replace a revoked token, keep the address.
        viewModelScope.launch {
            val last = container.accounts.lastOrigin() ?: return@launch
            _state.update { if (it.address.isEmpty()) it.copy(address = last) else it }
        }
    }

    fun onAddressChange(value: String) = _state.update { it.copy(address = value, addressError = null, error = null) }

    fun onTokenChange(value: String) = _state.update { it.copy(token = value.trim(), tokenError = null, error = null) }

    fun toggleTokenVisible() = _state.update { it.copy(tokenVisible = !it.tokenVisible) }

    fun dismissPrompt() = _state.update { it.copy(step = ConnectStep.Editing) }

    fun confirmHttp() {
        val current = _state.value
        httpConfirmedFor = runCatching { FlareOrigin.parse(current.address).toString() }.getOrNull()
        connect()
    }

    /** Called with the local network permission result. */
    fun onLocalNetworkResult(granted: Boolean) {
        if (granted) {
            connect()
        } else {
            _state.update { it.copy(step = ConnectStep.Editing, error = app.getString(R.string.connect_error_local_network_denied)) }
        }
    }

    fun connect() {
        val current = _state.value
        if (current.step == ConnectStep.Checking) return
        val origin = try {
            FlareOrigin.parse(current.address)
        } catch (_: InvalidOriginException) {
            _state.update { it.copy(step = ConnectStep.Editing, addressError = app.getString(R.string.connect_error_address)) }
            return
        }
        val credential = try {
            FlareCredential.of(current.token)
        } catch (_: IllegalArgumentException) {
            _state.update { it.copy(step = ConnectStep.Editing, tokenError = app.getString(R.string.connect_error_token_missing)) }
            return
        }
        _state.update { it.copy(step = ConnectStep.Checking, error = null) }
        viewModelScope.launch {
            // Plain HTTP only exposes the token when it crosses a network you don't control.
            val local = container.localNetwork.isLocal(origin) || LocalAddresses.isLoopbackHost(origin.host)
            if (!origin.isHttps && !local && httpConfirmedFor != origin.toString()) {
                _state.update { it.copy(step = ConnectStep.ConfirmHttp(origin.displayName)) }
                return@launch
            }
            if (container.localNetwork.needsPermission(origin)) {
                _state.update { it.copy(step = ConnectStep.NeedsLocalNetwork(origin.displayName)) }
                return@launch
            }
            try {
                val capabilities = container.connection.clientFor(origin, credential).probeCapabilities()
                if (!capabilities.uploadFiles.isGranted) {
                    val message = when (capabilities.uploadFiles) {
                        Access.DENIED_BY_ROLE -> R.string.connect_error_upload_role
                        Access.NOT_IN_TOKEN -> R.string.connect_error_upload_scope
                        // Flare answered in a way that says neither yes nor no.
                        else -> R.string.connect_error_upload_unknown
                    }
                    _state.update { it.copy(step = ConnectStep.Editing, error = app.getString(message)) }
                    return@launch
                }
                // Before saving: the saved account replaces the onboarding screens right away.
                container.justConnected.value = capabilities
                container.accounts.connect(origin, credential, capabilities, displayName = null)
                container.notifier.resolve(Attention.TOKEN_REJECTED)
                container.notifier.resolve(Attention.TOKEN_UNREADABLE)
                container.sync.reschedule(force = false)
                // Uploads queued before a disconnect continue with the new token.
                container.scheduler.kickUploads()
                _state.update { it.copy(step = ConnectStep.Connected(capabilities), token = "") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(step = ConnectStep.Editing, error = describeError(app, error, origin.displayName)) }
            }
        }
    }
}
