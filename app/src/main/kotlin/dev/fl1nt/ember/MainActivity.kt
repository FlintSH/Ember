package dev.fl1nt.ember

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dev.fl1nt.ember.ui.EmberRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val destination = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val container = (application as EmberApp).container
        var loaded = false
        splash.setKeepOnScreenCondition { !loaded }
        lifecycleScope.launch {
            container.accounts.account.first()
            loaded = true
        }
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            EmberRoot(container, destination, onDestinationHandled = { destination.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_DESTINATION)?.let { destination.value = it }
        // Lets the launcher rank the shortcuts people actually use.
        intent.getStringExtra(EXTRA_SHORTCUT_ID)?.let { ShortcutManagerCompat.reportShortcutUsed(this, it) }
    }

    companion object {
        const val EXTRA_DESTINATION = "dev.fl1nt.ember.extra.DESTINATION"
        const val EXTRA_SHORTCUT_ID = "dev.fl1nt.ember.extra.SHORTCUT_ID"
        const val DESTINATION_HOME = "home"
        const val DESTINATION_RECENT = "recent"
        const val DESTINATION_SYNC = "sync"
        const val DESTINATION_SETTINGS = "settings"
        const val DESTINATION_PASTE = "paste"
        const val DESTINATION_SHORTEN = "shorten"
        const val DESTINATION_UPLOAD = "upload"
        const val DESTINATION_SYNC_NOW = "sync-now"
    }
}
