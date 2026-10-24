package dev.fl1nt.ember.data.sync

import dev.fl1nt.ember.data.FlareConnection
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
 * Every file is recorded in the ledger once it's uploaded or permanently
 * rejected, so it's never sent twice. A run stops at [run]'s deadline and
 * leaves the rest for the next job window, because Android limits how long
 * background work may run.
 */
class SyncEngine(
    private val db: EmberDatabase,
    private val scanner: SourceScanner,
    private val uploader: Uploader,
    private val connection: FlareConnection,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val localNetworkBlocked: suspend (dev.fl1nt.ember.api.FlareOrigin) -> Boolean,
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

    private suspend fun runLocked(
        mediaOnly: Boolean,
        userInitiated: Boolean,
        deadlineMillis: Long,
        onProgress: (SyncProgress) -> Unit,
    ): SyncRun {
        val prefs = settings.current()
        if (!prefs.sync.enabled && !userInitiated) return SyncRun.Disabled
        val client = connection.client() ?: return SyncRun.NotConnected
        if (localNetworkBlocked(client.origin)) {
            notifier.attention(Attention.LOCAL_NETWORK)
            return SyncRun.Blocked(Attention.LOCAL_NETWORK)
        }

        val sources = db.sources().all().filter { it.enabled && (!mediaOnly || it.type == SourceType.MEDIA_ALBUM) }
        val now = clock()
        val work = ArrayList<Pair<SyncSourceEntity, SourceItem>>()
        var settling = 0
        for (source in sources) {
            if (source.type == SourceType.MEDIA_ALBUM && mediaAccess() == MediaAccess.NONE) {
                db.sources().recordScan(source.id, now, 0, MEDIA_ACCESS_ERROR)
                notifier.attention(Attention.MEDIA_ACCESS)
                continue
            }
            val items = try {
                scanner.scan(source)
            } catch (_: SecurityException) {
                db.sources().recordScan(source.id, now, 0, FOLDER_ACCESS_ERROR)
                notifier.attention(Attention.FOLDER_ACCESS, source.name)
                continue
            }
            val ledger = db.ledger().forSource(source.id).associateBy { it.itemKey }
            val plan = SyncPlanner.plan(source.type, source.sinceMillis, items, ledger, now, SyncPlanner.settleMillis(source.type, sdk))
            settling += plan.settling
            db.sources().recordScan(source.id, now, plan.toUpload.size + plan.settling, null)
            plan.toUpload.forEach { work += source to it }
        }

        val defaults = prefs.defaults
        var uploaded = 0
        var failed = 0
        work.forEachIndexed { index, (source, item) ->
            if (clock() > deadlineMillis) return SyncRun.Paused(uploaded, work.size - index)
            val status = SyncProgress(index, work.size, item.name)
            _progress.value = status
            onProgress(status)

            val entity = uploadFor(source, item, UploadPreset.decode(source.presetJson).over(defaults))
            when (val outcome = uploader.upload(entity, client)) {
                is Uploader.Outcome.Uploaded -> {
                    uploaded++
                    db.ledger().upsert(LedgerEntity(source.id, item.key, item.fingerprint, LedgerState.UPLOADED, entity.id, entity.attempts + 1, null, clock()))
                }
                is Uploader.Outcome.Failed -> when {
                    outcome.failure.kind == FailureKind.AUTH -> {
                        notifier.attention(Attention.TOKEN_REJECTED)
                        return SyncRun.Blocked(Attention.TOKEN_REJECTED)
                    }
                    outcome.upload.status == UploadStatus.QUEUED -> {
                        // Temporary. Leave it out of the ledger so the next run tries again.
                        return SyncRun.Retry(uploaded, outcome.failure.message)
                    }
                    else -> {
                        failed++
                        db.ledger().upsert(
                            LedgerEntity(source.id, item.key, item.fingerprint, LedgerState.FAILED, entity.id, outcome.upload.attempts, outcome.failure.message, clock()),
                        )
                    }
                }
            }
            db.sources().recordScan(source.id, clock(), (work.size - index - 1).coerceAtLeast(0), null)
        }
        if (uploaded + failed > 0 && sources.any { !it.notifyEach }) notifier.syncSummary(uploaded, failed)
        return SyncRun.Completed(uploaded, failed, settling)
    }

    /** Reuses the history row for a file that failed temporarily before. */
    private suspend fun uploadFor(source: SyncSourceEntity, item: SourceItem, preset: UploadPreset): UploadEntity {
        val existing = db.uploads().findSyncUpload(source.id, item.key)
        if (existing != null && existing.status != UploadStatus.DONE) return existing.copy(presetJson = preset.encode())
        val now = clock()
        val entity = UploadEntity(
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
            presetJson = preset.encode(),
            sealedPassword = null,
            status = UploadStatus.QUEUED,
            copyLink = source.copyLink,
            notify = source.notifyEach,
            createdAt = now,
            updatedAt = now,
        )
        db.uploads().insert(listOf(entity))
        return entity
    }

    companion object {
        const val MEDIA_ACCESS_ERROR = "media_access"
        const val FOLDER_ACCESS_ERROR = "folder_access"
    }
}
