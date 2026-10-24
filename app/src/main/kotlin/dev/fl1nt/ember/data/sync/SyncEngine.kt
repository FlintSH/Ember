package dev.fl1nt.ember.data.sync

import android.util.Log
import dev.fl1nt.ember.api.FlareClient
import dev.fl1nt.ember.api.FlareOrigin
import dev.fl1nt.ember.data.FlareConnection
import dev.fl1nt.ember.data.account.AccountProblem
import dev.fl1nt.ember.data.account.AccountStore
import dev.fl1nt.ember.data.db.EmberDatabase
import dev.fl1nt.ember.data.db.FailureKind
import dev.fl1nt.ember.data.db.LedgerEntity
import dev.fl1nt.ember.data.db.LedgerState
import dev.fl1nt.ember.data.db.SourceType
import dev.fl1nt.ember.data.db.SyncSourceEntity
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.data.db.UploadOrigin
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.data.settings.SettingsStore
import dev.fl1nt.ember.data.settings.UploadPreset
import dev.fl1nt.ember.data.uploads.Uploader
import dev.fl1nt.ember.system.MediaAccess
import dev.fl1nt.ember.work.Attention
import dev.fl1nt.ember.work.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Live progress of the current sync run. */
data class SyncProgress(val done: Int, val total: Int, val current: String?)

sealed interface SyncRun {
    /** [settling] files were still being written; a follow-up run picks them up. */
    data class Completed(val uploaded: Int, val failed: Int, val settling: Int = 0) : SyncRun

    /** Ran out of time in this job window. The rest continues in the next one. */
    data class Paused(val uploaded: Int, val remaining: Int) : SyncRun

    /** A temporary problem, such as losing signal. WorkManager retries with backoff. */
    data class Retry(val uploaded: Int, val reason: String) : SyncRun

    /** Needs someone to act, such as reconnecting or granting access. */
    data class Blocked(val reason: Attention) : SyncRun

    data object NotConnected : SyncRun

    data object Disabled : SyncRun
}

/**
 * Scans enabled sources and uploads new files, one run at a time.
 *
 * Every file is recorded in the ledger once it's uploaded, permanently
 * rejected, deleted, or cancelled, so it's never sent twice. A run stops at
 * [run]'s deadline and leaves the rest for the next job window, because
 * Android limits how long background work may run.
 */
class SyncEngine(
    private val db: EmberDatabase,
    private val scanner: SourceScanner,
    private val uploader: Uploader,
    private val connection: FlareConnection,
    private val accounts: AccountStore,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val localNetworkBlocked: suspend (FlareOrigin) -> Boolean,
    private val mediaAccess: () -> MediaAccess,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sdk: Int = android.os.Build.VERSION.SDK_INT,
) {
    private val mutex = Mutex()
    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    suspend fun run(
        mediaOnly: Boolean,
        userInitiated: Boolean,
        deadlineMillis: Long,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncRun = mutex.withLock {
        try {
            runLocked(mediaOnly, userInitiated, deadlineMillis, onProgress)
        } finally {
            _progress.value = null
        }
    }

    /**
     * Puts sync uploads that were cut off when Ember stopped back in the queue,
     * and drops unfinished ones whose folder was removed.
     */
    suspend fun recoverInterrupted() {
        // A run in progress did this when it started, so don't wait for it.
        if (!mutex.tryLock()) return
        try {
            recover()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun recover() {
        db.uploads().requeueInterruptedSync()
        db.uploads().deleteOrphanedSync()
    }

    private suspend fun runLocked(
        mediaOnly: Boolean,
        userInitiated: Boolean,
        deadlineMillis: Long,
        onProgress: (SyncProgress) -> Unit,
    ): SyncRun {
        recover()
        val prefs = settings.current()
        if (!prefs.sync.enabled && !userInitiated) return SyncRun.Disabled
        val client = connection.client()
        if (client == null) {
            if (accounts.current() == null) return SyncRun.NotConnected
            // Connected, but this phone can't read the token anymore.
            notifier.attention(Attention.TOKEN_UNREADABLE)
            return SyncRun.Blocked(Attention.TOKEN_UNREADABLE)
        }
        if (localNetworkBlocked(client.origin)) {
            notifier.attention(Attention.LOCAL_NETWORK)
            return SyncRun.Blocked(Attention.LOCAL_NETWORK)
        }

        val sources = db.sources().all().filter { it.enabled && (!mediaOnly || it.type == SourceType.MEDIA_ALBUM) }
        val now = clock()
        val work = ArrayList<Pair<SyncSourceEntity, SourceItem>>()
        val remaining = HashMap<Long, Int>()
        val settlingBySource = HashMap<Long, Int>()
        val retrying = HashSet<Pair<Long, String>>()
        for (source in sources) {
            val items = scan(source, now) ?: continue
            // A queued file that's no longer in the folder can never upload.
            val present = items.mapTo(HashSet()) { it.key }
            val queued = db.uploads().queuedForSource(source.id)
            queued.filter { it.syncItemKey !in present }.forEach { db.uploads().delete(it.id) }
            queued.filter { it.attempts > 0 }.forEach { row -> row.syncItemKey?.let { retrying += source.id to it } }

            val ledger = db.ledger().forSource(source.id).associateBy { it.itemKey }
            val plan = SyncPlanner.plan(source.type, source.sinceMillis, items, ledger, now, SyncPlanner.settleMillis(source.type, sdk))
            remaining[source.id] = plan.toUpload.size
            settlingBySource[source.id] = plan.settling
            db.sources().recordScan(source.id, now, plan.toUpload.size + plan.settling, null)
            plan.toUpload.forEach { work += source to it }
        }
        // Files that failed before go after fresh ones, so one stubborn file doesn't hold up the rest.
        work.sortBy { (source, item) -> (source.id to item.key) in retrying }

        val defaults = prefs.defaults
        var uploaded = 0
        var failed = 0
        var waiting = 0
        var lastWaitReason: String? = null
        var failingInARow = 0
        val goneInARow = HashMap<Long, Int>()
        // Sources that need attention, or failed in a way that would repeat for every file.
        val stopped = HashSet<Long>()
        work.forEachIndexed { index, (scanned, item) ->
            if (clock() > deadlineMillis) return SyncRun.Paused(uploaded, work.size - index)
            if (scanned.id in stopped) return@forEachIndexed
            // The folder may have been paused or removed, or sync turned off, since the scan.
            val source = db.sources().get(scanned.id)?.takeIf { it.enabled }
            if (source == null) {
                stopped += scanned.id
                return@forEachIndexed
            }
            if (prefs.sync.enabled && !settings.current().sync.enabled) return SyncRun.Disabled

            val status = SyncProgress(index, work.size, item.name)
            _progress.value = status
            onProgress(status)

            val pendingAfter = { left: Int -> left + (settlingBySource[source.id] ?: 0) }
            val left = (remaining.getValue(source.id) - 1).coerceAtLeast(0)
            remaining[source.id] = left
            val outcome = uploadItem(source, item, defaults, client)
            if (outcome !is Uploader.Outcome.Failed || outcome.failure.kind != FailureKind.TRANSIENT) failingInARow = 0
            if (outcome !is Uploader.Outcome.Failed || outcome.failure.kind != FailureKind.SOURCE_GONE) goneInARow.remove(source.id)
            when (outcome) {
                null -> Unit
                is Uploader.Outcome.Uploaded -> uploaded++
                is Uploader.Outcome.Failed -> when (outcome.failure.kind) {
                    FailureKind.AUTH -> {
                        accounts.reportProblem(AccountProblem.TOKEN_REJECTED)
                        notifier.attention(Attention.TOKEN_REJECTED)
                        return SyncRun.Blocked(Attention.TOKEN_REJECTED)
                    }
                    FailureKind.TRANSIENT -> {
                        // It stays queued and out of the ledger, so a later run tries again.
                        // Out of reach, nothing else would get through either; otherwise it
                        // may be about this file, so carry on unless several fail in a row.
                        if (outcome.failure.unreachable || ++failingInARow >= MAX_FAILING_IN_A_ROW) {
                            notifySummary(sources, uploaded, failed)
                            return SyncRun.Retry(uploaded, outcome.failure.message)
                        }
                        waiting++
                        lastWaitReason = outcome.failure.message
                        // Still waiting to upload, so it still counts.
                        remaining[source.id] = left + 1
                    }
                    FailureKind.SOURCE_GONE -> {
                        // Deleted since the scan, such as a discarded burst shot. Nothing to report,
                        // and no ledger entry: a deleted file won't be listed again, while one on a
                        // drive that's back will upload. Several in a row means the drive is gone.
                        db.uploads().delete(outcome.upload.id)
                        val gone = (goneInARow[source.id] ?: 0) + 1
                        goneInARow[source.id] = gone
                        if (gone >= MAX_FAILING_IN_A_ROW) stopped += source.id
                    }
                    FailureKind.NO_ACCESS -> {
                        // Not the file's fault. Keep it out of the ledger so it uploads once access is back.
                        db.uploads().delete(outcome.upload.id)
                        val album = source.type == SourceType.MEDIA_ALBUM
                        db.sources().recordScan(source.id, clock(), pendingAfter(left + 1), if (album) MEDIA_ACCESS_ERROR else FOLDER_ACCESS_ERROR)
                        notifier.attention(if (album) Attention.MEDIA_ACCESS else Attention.FOLDER_ACCESS, source.name)
                        stopped += source.id
                    }
                    FailureKind.TOO_LARGE, FailureKind.REJECTED -> {
                        failed++
                        db.ledger().upsert(
                            LedgerEntity(source.id, item.key, item.fingerprint, LedgerState.FAILED, outcome.upload.id, outcome.upload.attempts, outcome.failure.message, clock()),
                        )
                    }
                    FailureKind.PERMISSION, FailureKind.OTHER -> {
                        // Likely to repeat for every file, like an account that can't upload, so don't
                        // fail the whole folder in one go. The rest waits for the next sync.
                        failed++
                        db.ledger().upsert(
                            LedgerEntity(source.id, item.key, item.fingerprint, LedgerState.FAILED, outcome.upload.id, outcome.upload.attempts, outcome.failure.message, clock()),
                        )
                        db.sources().recordScan(source.id, clock(), pendingAfter(left), outcome.failure.message)
                        stopped += source.id
                    }
                }
            }
            if (source.id !in stopped) db.sources().recordScan(source.id, clock(), pendingAfter(remaining.getValue(source.id)), null)
        }
        notifySummary(sources, uploaded, failed)
        // Files that are waiting get another try soon, with WorkManager's backoff.
        lastWaitReason?.let { if (waiting > 0) return SyncRun.Retry(uploaded, it) }
        return SyncRun.Completed(uploaded, failed, settlingBySource.values.sum())
    }

    private fun notifySummary(sources: List<SyncSourceEntity>, uploaded: Int, failed: Int) {
        if (uploaded + failed > 0 && sources.any { !it.notifyEach }) notifier.syncSummary(uploaded, failed)
    }

    /** Lists a source's files, or records why it couldn't and returns null. One bad folder doesn't stop the rest. */
    private suspend fun scan(source: SyncSourceEntity, now: Long): List<SourceItem>? {
        if (source.type == SourceType.MEDIA_ALBUM && mediaAccess() == MediaAccess.NONE) {
            db.sources().recordScan(source.id, now, 0, MEDIA_ACCESS_ERROR)
            notifier.attention(Attention.MEDIA_ACCESS)
            return null
        }
        return try {
            scanner.scan(source)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            db.sources().recordScan(source.id, now, 0, FOLDER_ACCESS_ERROR)
            notifier.attention(Attention.FOLDER_ACCESS, source.name)
            null
        } catch (_: FolderMissingException) {
            db.sources().recordScan(source.id, now, 0, FOLDER_MISSING_ERROR)
            notifier.attention(Attention.FOLDER_MISSING, source.name)
            null
        } catch (error: Exception) {
            // Usually a document provider or the media library having a bad moment. The next sync tries again.
            Log.w(TAG, "Couldn't list ${source.name}", error)
            db.sources().recordScan(source.id, now, source.pendingCount, SCAN_ERROR)
            null
        }
    }

    /**
     * Uploads one file, reusing its history row from an earlier try. Returns null
     * when there's nothing to send: the file was already uploaded before Ember
     * stopped, or someone cancelled it.
     */
    private suspend fun uploadItem(source: SyncSourceEntity, item: SourceItem, defaults: UploadPreset, client: FlareClient): Uploader.Outcome? {
        val preset = UploadPreset.decode(source.presetJson).over(defaults).encode()
        val existing = db.uploads().findSyncUpload(source.id, item.key)
        val entity = when (existing?.status) {
            UploadStatus.DONE, UploadStatus.CANCELLED -> {
                val state = if (existing.status == UploadStatus.DONE) LedgerState.UPLOADED else LedgerState.SKIPPED
                db.ledger().upsert(LedgerEntity(source.id, item.key, item.fingerprint, state, existing.id, existing.attempts, null, clock()))
                return null
            }
            UploadStatus.QUEUED, UploadStatus.RUNNING, UploadStatus.FAILED -> existing.copy(
                presetJson = preset,
                fileName = item.name,
                mimeType = item.mimeType,
                sizeBytes = item.size,
                sourceUri = item.uri,
                // A failed file is back after "Try failed files again" or Retry, so it gets a fresh set of attempts.
                attempts = if (existing.status == UploadStatus.FAILED) 0 else existing.attempts,
            )
            null -> {
                val now = clock()
                UploadEntity(
                    id = UUID.randomUUID().toString(),
                    batchId = null,
                    origin = UploadOrigin.SYNC,
                    isPaste = false,
                    fileName = item.name,
                    mimeType = item.mimeType,
                    sizeBytes = item.size,
                    stagedPath = null,
                    sourceUri = item.uri,
                    syncSourceId = source.id,
                    syncItemKey = item.key,
                    presetJson = preset,
                    sealedPassword = null,
                    status = UploadStatus.QUEUED,
                    copyLink = source.copyLink,
                    notify = source.notifyEach,
                    createdAt = now,
                    updatedAt = now,
                ).also { db.uploads().insert(listOf(it)) }
            }
        }
        val outcome = uploader.upload(entity, client)
        if (outcome is Uploader.Outcome.Uploaded) {
            db.ledger().upsert(LedgerEntity(source.id, item.key, item.fingerprint, LedgerState.UPLOADED, entity.id, outcome.upload.attempts, null, clock()))
        }
        return outcome
    }

    companion object {
        /** Codes for [SyncSourceEntity.lastError]. Anything else is a message from Flare. */
        const val MEDIA_ACCESS_ERROR = "media_access"
        const val FOLDER_ACCESS_ERROR = "folder_access"
        const val FOLDER_MISSING_ERROR = "folder_missing"
        const val SCAN_ERROR = "scan_failed"
        private const val TAG = "EmberSync"

        /** This many failures in a row point at something bigger than one file. */
        private const val MAX_FAILING_IN_A_ROW = 3
    }
}
