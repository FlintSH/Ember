package dev.fl1nt.ember.data.uploads

import android.content.Context
import android.net.Uri
import dev.fl1nt.ember.data.account.SecretCipher
import dev.fl1nt.ember.data.db.EmberDatabase
import dev.fl1nt.ember.data.db.FailureKind
import dev.fl1nt.ember.data.db.LedgerEntity
import dev.fl1nt.ember.data.db.LedgerState
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.data.db.UploadOrigin
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.data.settings.UploadPreset
import dev.fl1nt.ember.system.describeError
import dev.fl1nt.ember.work.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Queues uploads from the share sheet, the file picker, and pastes. */
class UploadRepository(
    private val context: Context,
    private val db: EmberDatabase,
    private val staging: UploadStaging,
    private val cipher: SecretCipher,
    private val scheduler: WorkScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeRecent(limit: Int = 200): Flow<List<UploadEntity>> = db.uploads().observeRecent(limit)

    fun observeBatch(batchId: String): Flow<List<UploadEntity>> = db.uploads().observeBatch(batchId)

    fun observeActiveCount(): Flow<Int> = db.uploads().observeActiveCount()

    /**
     * Copies each file into Ember's storage and queues it. Returns the batch ID.
     * Files that can't be read are recorded as failed so nothing silently vanishes.
     */
    suspend fun enqueueFiles(
        uris: List<Uri>,
        origin: UploadOrigin,
        preset: UploadPreset,
        password: String? = null,
        copyLinkWhenSingle: Boolean = true,
        notify: Boolean = true,
    ): String {
        require(uris.isNotEmpty()) { "Nothing to upload" }
        val batchId = UUID.randomUUID().toString()
        val start = clock()
        val sealedPassword = password?.takeIf { it.isNotEmpty() }?.let(cipher::encrypt)
        val entities = uris.mapIndexed { index, uri ->
            val id = UUID.randomUUID().toString()
            val base = UploadEntity(
                id = id,
                batchId = batchId,
                origin = origin,
                isPaste = false,
                fileName = UploadStaging.safeName(staging.describe(uri).first, null),
                mimeType = null,
                sizeBytes = null,
                stagedPath = null,
                sourceUri = null,
                syncSourceId = null,
                syncItemKey = null,
                presetJson = preset.encode(),
                sealedPassword = sealedPassword,
                status = UploadStatus.QUEUED,
                copyLink = copyLinkWhenSingle && uris.size == 1,
                notify = notify,
                createdAt = start + index,
                updatedAt = start + index,
            )
            try {
                val staged = staging.stage(uri, id)
                base.copy(fileName = staged.displayName, mimeType = staged.mimeType, sizeBytes = staged.size, stagedPath = staged.file.path)
            } catch (cancelled: CancellationException) {
                staging.discard(id)
                throw cancelled
            } catch (error: Exception) {
                // Reading another app's file failed, so there's no copy to retry from.
                val kind = Failure.kindOf(error)
                base.copy(
                    status = UploadStatus.FAILED,
                    failureKind = if (kind == FailureKind.TRANSIENT) FailureKind.OTHER else kind,
                    failureMessage = describeError(context, error),
                    sealedPassword = null,
                    completedAt = start,
                )
            }
        }
        db.uploads().insert(entities)
        if (entities.any { it.status == UploadStatus.QUEUED }) scheduler.kickUploads()
        return batchId
    }

    /** Queues text as a Flare paste. Returns the batch ID. */
    suspend fun enqueuePaste(
        text: String,
        fileName: String,
        origin: UploadOrigin,
        preset: UploadPreset,
        password: String? = null,
        copyLink: Boolean = true,
        notify: Boolean = true,
    ): String {
        require(text.isNotBlank()) { "Paste content can't be blank" }
        val batchId = UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        val staged = staging.stageText(text, fileName, id)
        val now = clock()
        db.uploads().insert(
            listOf(
                UploadEntity(
                    id = id,
                    batchId = batchId,
                    origin = origin,
                    isPaste = true,
                    fileName = staged.displayName,
                    mimeType = "text/plain",
                    sizeBytes = staged.size,
                    stagedPath = staged.file.path,
                    sourceUri = null,
                    syncSourceId = null,
                    syncItemKey = null,
                    presetJson = preset.encode(),
                    sealedPassword = password?.takeIf { it.isNotEmpty() }?.let(cipher::encrypt),
                    status = UploadStatus.QUEUED,
                    copyLink = copyLink,
                    notify = notify,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        scheduler.kickUploads()
        return batchId
    }

    /** Tries a failed upload again, if Ember still has something to send. */
    suspend fun retry(id: String): Boolean {
        val upload = db.uploads().get(id) ?: return false
        if (!upload.canRetry) return false
        db.uploads().update(
            upload.copy(status = UploadStatus.QUEUED, attempts = 0, failureKind = null, failureMessage = null, completedAt = null, updatedAt = clock()),
        )
        val sourceId = upload.syncSourceId
        val itemKey = upload.syncItemKey
        if (sourceId != null && itemKey != null) {
            // Sync skips files its ledger already settled. Forget the failure so the next sync sends it.
            db.ledger().delete(sourceId, itemKey)
            scheduler.syncNow(userInitiated = true)
        } else {
            scheduler.kickUploads()
        }
        return true
    }

    /**
     * Copies a shared file in again, for a share that failed before Ember had its
     * own copy, such as on a full disk. Only the share sheet can do this, while it
     * still has permission to read the original.
     */
    suspend fun restage(id: String, uri: Uri, password: String?): Boolean {
        val upload = db.uploads().get(id) ?: return false
        if (upload.status != UploadStatus.FAILED || upload.stagedPath != null) return false
        val staged = try {
            staging.stage(uri, id)
        } catch (cancelled: CancellationException) {
            staging.discard(id)
            throw cancelled
        } catch (error: Exception) {
            val kind = Failure.kindOf(error)
            db.uploads().update(
                upload.copy(
                    failureKind = if (kind == FailureKind.TRANSIENT) FailureKind.OTHER else kind,
                    failureMessage = describeError(context, error),
                    updatedAt = clock(),
                ),
            )
            return false
        }
        db.uploads().update(
            upload.copy(
                status = UploadStatus.QUEUED,
                fileName = staged.displayName,
                mimeType = staged.mimeType,
                sizeBytes = staged.size,
                stagedPath = staged.file.path,
                sealedPassword = password?.takeIf { it.isNotEmpty() }?.let(cipher::encrypt),
                attempts = 0,
                failureKind = null,
                failureMessage = null,
                completedAt = null,
                updatedAt = clock(),
            ),
        )
        scheduler.kickUploads()
        return true
    }

    suspend fun cancel(id: String) {
        val upload = db.uploads().get(id) ?: return
        if (upload.status != UploadStatus.QUEUED) return
        db.uploads().update(upload.copy(status = UploadStatus.CANCELLED, stagedPath = null, sealedPassword = null, chunkProgress = null, updatedAt = clock()))
        staging.delete(upload.stagedPath)
        val sourceId = upload.syncSourceId
        val itemKey = upload.syncItemKey
        if (sourceId != null && itemKey != null) {
            // Remember the choice, or the next sync would upload the file anyway.
            db.ledger().upsert(LedgerEntity(sourceId, itemKey, CANCELLED_FINGERPRINT, LedgerState.SKIPPED, upload.id, upload.attempts, null, clock()))
        }
    }

    suspend fun remove(id: String) {
        val upload = db.uploads().get(id) ?: return
        if (upload.status == UploadStatus.RUNNING) return
        db.uploads().delete(id)
        staging.delete(upload.stagedPath)
    }

    suspend fun clearFinished() {
        db.uploads().finishedWithStagedFiles().forEach { staging.delete(it.stagedPath) }
        db.uploads().clearFinished()
    }

    private companion object {
        const val CANCELLED_FINGERPRINT = "cancelled"
    }
}

/**
 * Whether trying again can work: Ember still has the file, or can read it again,
 * and the failure wasn't that the file is gone.
 */
val UploadEntity.canRetry: Boolean
    get() = status == UploadStatus.FAILED && failureKind != FailureKind.SOURCE_GONE && (stagedPath != null || sourceUri != null)
