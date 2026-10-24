package dev.fl1nt.ember.work

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.fl1nt.ember.data.settings.SettingsStore
import dev.fl1nt.ember.data.settings.SyncPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Enqueues Ember's background work. */
class WorkScheduler(private val context: Context, private val settings: SettingsStore) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** Starts, or appends to, the queue of shares, pastes, and picked files. */
    fun kickUploads() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(UPLOADS, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Keeps automatic sync scheduled: a periodic safety net for every source, plus
     * a MediaStore trigger so new photos and screenshots upload within seconds.
     */
    fun configureSync(prefs: SyncPreferences, hasSources: Boolean, hasMediaSources: Boolean, force: Boolean) {
        if (!prefs.enabled || !hasSources) {
            workManager.cancelUniqueWork(SYNC_PERIODIC)
            workManager.cancelUniqueWork(SYNC_MEDIA)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(prefs.intervalMinutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(syncConstraints(prefs))
            .setInputData(workDataOf(SyncWorker.KEY_REASON to SyncWorker.REASON_PERIODIC))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(SYNC_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        if (hasMediaSources) {
            armMediaTrigger(prefs, if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
        } else {
            workManager.cancelUniqueWork(SYNC_MEDIA)
        }
    }

    /**
     * Runs the next media check when MediaStore changes. Trigger work runs once, so
     * the sync worker re-arms it after each run with [ExistingWorkPolicy.APPEND_OR_REPLACE].
     */
    fun armMediaTrigger(prefs: SyncPreferences, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(prefs.chargingOnly)
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(2))
            .setTriggerContentMaxDelay(Duration.ofSeconds(10))
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_REASON to SyncWorker.REASON_MEDIA))
            .build()
        workManager.enqueueUniqueWork(SYNC_MEDIA, policy, request)
    }

    /**
     * Syncs now. Someone tapping "Sync now" doesn't want to wait for a charger, but
     * Ember still respects "Wi-Fi only" because mobile data can cost money.
     */
    suspend fun syncNow(userInitiated: Boolean) {
        val wifiOnly = settings.current().sync.wifiOnly
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(
                workDataOf(SyncWorker.KEY_REASON to if (userInitiated) SyncWorker.REASON_MANUAL else SyncWorker.REASON_CONTINUE),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(SYNC_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Continues a large sync in a new job window. */
    fun continueSync(prefs: SyncPreferences) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(syncConstraints(prefs))
            .setInputData(workDataOf(SyncWorker.KEY_REASON to SyncWorker.REASON_CONTINUE))
            .build()
        workManager.enqueueUniqueWork(SYNC_CONTINUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Whether any sync job is running right now. */
    fun observeSyncRunning(): Flow<Boolean> {
        fun running(name: String) = workManager.getWorkInfosForUniqueWorkFlow(name)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        return combine(running(SYNC_PERIODIC), running(SYNC_MEDIA), running(SYNC_NOW), running(SYNC_CONTINUE)) { states ->
            states.any { it }
        }
    }

    fun observeUploadsRunning(): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(UPLOADS).map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

    fun cancelAll() {
        listOf(UPLOADS, SYNC_PERIODIC, SYNC_MEDIA, SYNC_NOW, SYNC_CONTINUE).forEach(workManager::cancelUniqueWork)
    }

    private fun syncConstraints(prefs: SyncPreferences) = Constraints.Builder()
        .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(prefs.chargingOnly)
        .setRequiresBatteryNotLow(true)
        .build()

    companion object {
        const val UPLOADS = "ember.uploads"
        const val SYNC_PERIODIC = "ember.sync.periodic"
        const val SYNC_MEDIA = "ember.sync.media"
        const val SYNC_NOW = "ember.sync.now"
        const val SYNC_CONTINUE = "ember.sync.continue"
    }
}
