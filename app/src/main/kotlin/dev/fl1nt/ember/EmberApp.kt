package dev.fl1nt.ember

import android.app.Application
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.system.AppShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
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
    }

    private suspend fun startUp() {
        val c = container
        // Launcher shortcuts only make sense while connected.
        appScope.launch {
            c.accounts.account.distinctUntilChangedBy { it != null }.collect { account ->
                if (account != null) AppShortcuts.publish(this@EmberApp) else AppShortcuts.clear(this@EmberApp)
            }
        }
        if (c.accounts.current() == null) return
        c.sync.reschedule(force = false)
        // Pick up uploads that were waiting when the app last stopped.
        val recent = c.database.uploads().observeRecent(500).first()
        if (recent.any { it.status == UploadStatus.QUEUED && it.syncSourceId == null }) c.scheduler.kickUploads()
        c.staging.prune(recent.filter { it.stagedPath != null }.map { it.id }.toSet())
    }
}
