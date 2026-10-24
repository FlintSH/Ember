package dev.fl1nt.ember.data.uploads

import android.net.Uri
import dev.fl1nt.ember.data.account.SecretCipher
import dev.fl1nt.ember.data.db.EmberDatabase
import dev.fl1nt.ember.data.db.FailureKind
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.data.db.UploadOrigin
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.data.settings.UploadPreset
import dev.fl1nt.ember.work.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Queues uploads from the share sheet, the file picker, and pastes. */
class UploadRepository(
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
                val failure = Failure.of(error)
                base.copy(
                    status = UploadStatus.FAILED,
                    failureKind = if (failure.kind == FailureKind.TRANSIENT) FailureKind.SOURCE_GONE else failure.kind,
                    failureMessage = failure.message,
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
        if (upload.status != UploadStatus.FAILED || (upload.stagedPath == null && upload.sourceUri == null)) return false
        db.uploads().update(
            upload.copy(status = UploadStatus.QUEUED, attempts = 0, failureKind = null, failureMessage = null, completedAt = null, updatedAt = clock()),
        )
        if (upload.syncSourceId != null) scheduler.syncNow(userInitiated = true) else scheduler.kickUploads()
        return true
    }

    suspend fun cancel(id: String) {
        val upload = db.uploads().get(id) ?: return
        if (upload.status != UploadStatus.QUEUED) return
        db.uploads().update(upload.copy(status = UploadStatus.CANCELLED, stagedPath = null, sealedPassword = null, updatedAt = clock()))
        staging.delete(upload.stagedPath)
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
}
