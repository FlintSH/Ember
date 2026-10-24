package dev.fl1nt.ember.ui.share

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fl1nt.ember.EmberApp
import dev.fl1nt.ember.MainActivity
import dev.fl1nt.ember.data.settings.EmberSettings
import dev.fl1nt.ember.system.Clipboard
import dev.fl1nt.ember.ui.LocalAppContainer
import dev.fl1nt.ember.ui.theme.EmberTheme

/**
 * Handles "Upload to Flare" in the share sheet, "Paste to Flare" in the text
 * selection menu, and the Quick Settings paste tile.
 */
class ShareActivity : ComponentActivity() {

    private val container get() = (application as EmberApp).container
    private val viewModel: ShareViewModel by viewModels { viewModelFactory { initializer { ShareViewModel(container) } } }
    private lateinit var request: ShareRequest

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        request = ShareRequest.from(intent)
        viewModel.start(request)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(EmberSettings())
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.phase, state.links) {
                // Replace the selected text with the link, then step out of the way.
                if (state.phase == SharePhase.DONE && state.returnsToSelection && state.links.isNotEmpty()) {
                    setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, state.links.first()))
                    finish()
                }
            }
            EmberTheme(settings.themeMode) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    ShareScreen(
                        state = state,
                        callbacks = ShareCallbacks(
                            onDismiss = ::finish,
                            onOpenApp = {
                                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                finish()
                            },
                            onUploadFiles = viewModel::uploadFiles,
                            onSubmitText = viewModel::submitText,
                            onTextAction = viewModel::onTextAction,
                            onPasteName = viewModel::onPasteName,
                            onVisibility = viewModel::onVisibility,
                            onExpiration = viewModel::onExpiration,
                            onFolder = viewModel::onFolder,
                            onPassword = viewModel::onPassword,
                            onRetry = viewModel::retry,
                        ),
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) viewModel.onFocused { Clipboard.readText(this) }
    }

    companion object {
        const val ACTION_PASTE_CLIPBOARD = "dev.fl1nt.ember.action.PASTE_CLIPBOARD"
    }
}
