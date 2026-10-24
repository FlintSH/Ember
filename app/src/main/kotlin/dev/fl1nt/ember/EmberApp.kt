package dev.fl1nt.ember

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.fl1nt.ember.system.AppShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class EmberApp : Application() {

    lateinit var container: AppContainer
        private set

    /** Work that should outlive any screen, such as keeping schedules current. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        appScope.launch { startUp() }
        // Uploads can wait on things people fix outside Ember, like local network access
        // in system settings. Coming back to Ember picks them up again.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                appScope.launch { resumeUploads() }
            }
        })
    }

    private suspend fun startUp() {
        val c = container
        // Launcher shortcuts only make sense while connected.
        appScope.launch {
            c.accounts.account.distinctUntilChangedBy { it != null }.collect { account ->
                if (account != null) AppShortcuts.publish(this@EmberApp) else AppShortcuts.clear(this@EmberApp)
            }
        }
        c.syncEngine.recoverInterrupted()
        c.staging.prune(c.database.uploads().stagedIds().toSet())
        if (c.accounts.current() == null) return
        c.sync.reschedule(force = false)
        resumeUploads()
    }

    /** Restarts the share queue when uploads wait and nothing stands in their way. */
    private suspend fun resumeUploads() {
        val c = container
        val account = c.accounts.current() ?: return
        if (account.problem != null || c.localNetwork.needsPermission(account.origin)) return
        c.scheduler.resumeUploads(c.database.uploads().queuedCount())
    }
}
