package dev.fl1nt.ember.work

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.fl1nt.ember.EmberApp
import dev.fl1nt.ember.data.db.FailureKind
import dev.fl1nt.ember.data.db.SourceType
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.data.sync.SyncRun
import dev.fl1nt.ember.data.uploads.Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Uploads queued shares, pastes, and picked files, one at a time. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container get() = (applicationContext as EmberApp).container

    override suspend fun getForegroundInfo(): ForegroundInfo =
        container.notifier.uploadForeground(fileName = "", sent = 0, total = -1, remaining = 0)

    override suspend fun doWork(): Result {
        val c = container
        val dao = c.database.uploads()
        dao.requeueInterrupted()
        val client = c.connection.client() ?: return Result.failure()
        if (c.localNetwork.needsPermission(client.origin)) {
            c.notifier.attention(Attention.LOCAL_NETWORK)
            return Result.failure()
        }
        var promoted = false
        try {
            while (true) {
                val next = dao.nextQueued() ?: break
                val remaining = (dao.activeCount() - 1).coerceAtLeast(0)
                if (!promoted) promoted = tryForeground(c.notifier.uploadForeground(next.fileName, 0, next.sizeBytes ?: -1, remaining))
                val outcome = c.uploader.upload(next, client) { sent, total ->
                    c.notifier.showUploadProgress(next.fileName, sent, total, remaining)
                }
                if (outcome is Uploader.Outcome.Failed) {
                    when {
                        outcome.failure.kind == FailureKind.AUTH -> {
                            c.notifier.attention(Attention.TOKEN_REJECTED)
                            return Result.failure()
                        }
                        outcome.upload.status == UploadStatus.QUEUED -> return Result.retry()
                    }
                }
            }
        } finally {
            c.notifier.cancel(Notifier.ID_UPLOAD_PROGRESS)
        }
        return Result.success()
    }

    /** Shows a foreground notification when Android allows it, so long uploads aren't cut off. */
    private suspend fun tryForeground(info: ForegroundInfo): Boolean = try {
        setForeground(info)
        true
    } catch (_: IllegalStateException) {
        // Android 12+ refuses foreground services started from the background.
        false
    }
}

/** Scans sync sources and uploads new files. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container get() = (applicationContext as EmberApp).container

    override suspend fun getForegroundInfo(): ForegroundInfo = container.notifier.syncForeground(0, 0, null)

    override suspend fun doWork(): Result {
        val c = container
        val reason = inputData.getString(KEY_REASON) ?: REASON_PERIODIC
        val userInitiated = reason == REASON_MANUAL
        val prefs = c.settings.current().sync
        // Foreground status keeps a long manual sync alive; Android only allows it while Ember is visible.
        val promoted = (userInitiated || appIsVisible()) && tryForeground(c.notifier.syncForeground(0, 0, null))
        val result = try {
            c.syncEngine.run(
                mediaOnly = reason == REASON_MEDIA,
                userInitiated = userInitiated,
                deadlineMillis = System.currentTimeMillis() + if (promoted) FOREGROUND_BUDGET_MILLIS else RUN_BUDGET_MILLIS,
            ) { progress ->
                c.notifier.showSyncProgress(progress.done, progress.total, progress.current)
            }
        } finally {
            withContext(NonCancellable) {
                c.notifier.cancel(Notifier.ID_SYNC_PROGRESS)
                // Trigger work runs once; watch MediaStore again only while an album still syncs.
                val albumsSync = c.database.sources().all().any { it.enabled && it.type == SourceType.MEDIA_ALBUM }
                if (reason == REASON_MEDIA && prefs.enabled && albumsSync) c.scheduler.armMediaTrigger(prefs)
            }
        }
        return when (result) {
            is SyncRun.Completed -> {
                // Files still being written get another look shortly, not at the next scheduled check.
                if (result.settling > 0) c.scheduler.continueSync(prefs, delay = SETTLE_RECHECK)
                Result.success()
            }
            SyncRun.Disabled, SyncRun.NotConnected -> Result.success()
            is SyncRun.Paused -> {
                c.scheduler.continueSync(prefs)
                Result.success()
            }
            is SyncRun.Retry -> Result.retry()
            is SyncRun.Blocked -> Result.failure()
        }
    }

    private suspend fun tryForeground(info: ForegroundInfo): Boolean = try {
        setForeground(info)
        true
    } catch (_: IllegalStateException) {
        false
    }

    private suspend fun appIsVisible(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    companion object {
        const val KEY_REASON = "reason"
        const val REASON_PERIODIC = "periodic"
        const val REASON_MEDIA = "media"
        const val REASON_MANUAL = "manual"
        const val REASON_CONTINUE = "continue"

        /** Stop before Android's ten minute limit for regular jobs. */
        private const val RUN_BUDGET_MILLIS = 8 * 60 * 1000L

        /** Foreground work may run longer, but still yields so the queue stays fair. */
        private const val FOREGROUND_BUDGET_MILLIS = 30 * 60 * 1000L

        private val SETTLE_RECHECK: java.time.Duration = java.time.Duration.ofMinutes(1)
    }
}
