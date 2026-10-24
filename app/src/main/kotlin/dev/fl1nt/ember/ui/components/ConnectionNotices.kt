package dev.fl1nt.ember.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import dev.fl1nt.ember.R
import dev.fl1nt.ember.data.account.AccountProblem
import dev.fl1nt.ember.system.LocalNetworkAccess
import dev.fl1nt.ember.ui.icons.Lucide

/** Asks to reconnect when Flare stopped accepting the token, or this phone can't read it. */
@Composable
fun AccountProblemNotice(problem: AccountProblem, onReconnect: () -> Unit) {
    val rejected = problem == AccountProblem.TOKEN_REJECTED
    Notice(
        title = stringResource(if (rejected) R.string.attention_token_title else R.string.attention_token_unreadable_title),
        text = stringResource(if (rejected) R.string.problem_token_rejected else R.string.problem_token_unreadable),
        tone = Tone.DANGER,
        icon = Lucide.KeyRound,
        action = { GhostButton(stringResource(R.string.action_reconnect), onClick = onReconnect, icon = Lucide.RefreshCw) },
    )
}

/** Asks for the local network access a Flare server at home needs on Android 17 and later. */
@Composable
fun LocalNetworkNotice(onAllow: () -> Unit) {
    val context = LocalContext.current
    Notice(
        title = stringResource(R.string.connect_local_title),
        text = stringResource(R.string.local_network_needed),
        tone = Tone.WARNING,
        icon = Lucide.Network,
        action = {
            Row {
                GhostButton(stringResource(R.string.action_allow), onClick = onAllow)
                GhostButton(stringResource(R.string.action_open_settings), onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                })
            }
        },
    )
}

/** Returns a function that asks for local network access and reports whether it was granted. */
@Composable
fun rememberLocalNetworkRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return remember(launcher) {
        { LocalNetworkAccess.PERMISSION?.let(launcher::launch) ?: onResult(true) }
    }
}
