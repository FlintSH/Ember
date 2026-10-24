package dev.fl1nt.ember.data.uploads

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import dev.fl1nt.ember.R
import dev.fl1nt.ember.api.FileUploadSource
import dev.fl1nt.ember.api.FlareClient
import dev.fl1nt.ember.api.UploadKind
import dev.fl1nt.ember.api.UploadSource
import dev.fl1nt.ember.api.UploadedFile
import dev.fl1nt.ember.data.account.AccountStore
import dev.fl1nt.ember.data.account.SecretCipher
import dev.fl1nt.ember.data.db.EmberDatabase
import dev.fl1nt.ember.data.db.FailureKind
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.data.db.UploadOrigin
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.data.settings.LinkFormat
import dev.fl1nt.ember.data.settings.SettingsStore
import dev.fl1nt.ember.data.settings.UploadPreset
import dev.fl1nt.ember.system.Clipboard
import dev.fl1nt.ember.system.describeError
import dev.fl1nt.ember.work.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.GeneralSecurityException

/** Reads a content URI, such as a MediaStore photo or a document in a synced folder. */
class ContentUploadSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val fileName: String,
    override val declaredMimeType: String?,
    override val size: Long?,
) : UploadSource {
    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw FileNotFoundException("Couldn't open $fileName")
}

/**
 * Sends one [UploadEntity] to Flare and records the outcome. Shared by the share
 * queue and background sync.
 */
class Uploader(
    private val context: Context,
    private val db: EmberDatabase,
    private val staging: UploadStaging,
    private val cipher: SecretCipher,
    private val accounts: AccountStore,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Outcome {
        val upload: UploadEntity

        data class Uploaded(override val upload: UploadEntity, val link: String) : Outcome

        data class Failed(override val upload: UploadEntity, val failure: Failure) : Outcome
    }

    suspend fun upload(entity: UploadEntity, client: FlareClient, onProgress: (Long, Long) -> Unit = { _, _ -> }): Outcome {
        val dao = db.uploads()
        val running = entity.copy(
            status = UploadStatus.RUNNING,
            attempts = entity.attempts + 1,
            bytesSent = 0,
            failureKind = null,
            failureMessage = null,
            updatedAt = clock(),
        )
        dao.update(running)
        // Never upload without a password someone asked for.
        val password = try {
            running.sealedPassword?.let(cipher::decrypt)
        } catch (_: GeneralSecurityException) {
            return failLocked(running)
        } catch (_: IllegalArgumentException) {
            return failLocked(running)
        }
        val result = try {
            coroutineScope {
                val sent = MutableStateFlow(0L)
                // Persist progress a couple of times a second without blocking the upload thread.
                val progressWriter = launch {
                    var written = -1L
                    while (isActive) {
                        delay(PROGRESS_INTERVAL_MILLIS)
                        val value = sent.value
                        if (value != written) {
                            dao.updateProgress(running.id, value, clock())
                            written = value
                        }
                    }
                }
                try {
                    client.upload(
                        source = sourceFor(running),
                        options = UploadPreset.decode(running.presetJson).toOptions(password),
                        kind = if (running.isPaste) UploadKind.PASTE else UploadKind.FILE,
                        progress = { bytes, total ->
                            sent.value = bytes
                            onProgress(bytes, total)
                        },
                    )
                } finally {
                    progressWriter.cancel()
                }
            }
        } catch (cancelled: CancellationException) {
            // Being stopped isn't a failed attempt.
            withContext(NonCancellable) {
                dao.update(running.copy(status = UploadStatus.QUEUED, attempts = entity.attempts, bytesSent = 0, updatedAt = clock()))
            }
            throw cancelled
        } catch (error: Exception) {
            return fail(running, Failure.of(error, describeError(context, error, client.origin.displayName)))
        }
        // Flare has the file. Finish recording that even if Ember is being stopped,
        // or the next run would upload it again.
        return withContext(NonCancellable) { succeed(running, result) }
    }

    private suspend fun succeed(upload: UploadEntity, result: UploadedFile): Outcome {
        val prefs = settings.current()
        val page = result.shareUrl
        val link = if (prefs.linkFormat == LinkFormat.RAW_FILE) result.rawUrl ?: page else page
        val done = upload.copy(
            status = UploadStatus.DONE,
            bytesSent = result.size,
            sizeBytes = result.size,
            mimeType = result.type.ifEmpty { upload.mimeType },
            pageUrl = page,
            rawUrl = result.rawUrl,
            sealedPassword = null,
            stagedPath = null,
            completedAt = clock(),
            updatedAt = clock(),
        )
        db.uploads().update(done)
        staging.delete(upload.stagedPath)
        accounts.learnPublicOrigin(page)
        accounts.clearProblem()
        // Synced folders carry their own copy and notify choices; everything else follows Settings.
        val fromSync = upload.origin == UploadOrigin.SYNC
        if (upload.copyLink && (fromSync || prefs.copyLinkAfterUpload)) Clipboard.copy(context, link, confirm = false)
        if (upload.notify && (fromSync || prefs.notifyWhenDone)) notifier.uploaded(upload.id, upload.fileName, link)
        return Outcome.Uploaded(done, link)
    }

    /**
     * Records a failure. Temporary ones go back to the queue. When Flare can't be
     * reached at all, the upload waits without using up an attempt, because a server
     * that's only reachable at home shouldn't fail every photo taken elsewhere. Other
     * temporary failures give up after [MAX_ATTEMPTS], or [SYNC_MAX_ATTEMPTS] for sync.
     * A rejected token keeps everything queued until someone reconnects. The sealed
     * password stays, so trying again still sends it.
     */
    private suspend fun fail(upload: UploadEntity, failure: Failure): Outcome {
        val waitsForReconnect = failure.kind == FailureKind.AUTH
        val attempts = if (failure.unreachable) upload.attempts - 1 else upload.attempts
        val limit = if (upload.origin == UploadOrigin.SYNC) SYNC_MAX_ATTEMPTS else MAX_ATTEMPTS
        val retryable = waitsForReconnect || (failure.isTransient && (failure.unreachable || attempts < limit))
        // Out of retries: report the last cause instead of "temporary".
        val kind = if (!retryable && failure.kind == FailureKind.TRANSIENT) FailureKind.OTHER else failure.kind
        val updated = upload.copy(
            status = if (retryable) UploadStatus.QUEUED else UploadStatus.FAILED,
            attempts = attempts,
            bytesSent = 0,
            failureKind = kind,
            failureMessage = failure.message,
            updatedAt = clock(),
            completedAt = if (retryable) null else clock(),
        )
        db.uploads().update(updated)
        // Sync handles a deleted or unreadable file itself: it skips the file, or asks for access.
        val syncHandles = upload.origin == UploadOrigin.SYNC && (kind == FailureKind.SOURCE_GONE || kind == FailureKind.NO_ACCESS)
        if (!retryable && upload.notify && kind != FailureKind.AUTH && !syncHandles) {
            notifier.uploadFailed(upload.id, upload.fileName, failure.message)
        }
        return Outcome.Failed(updated, failure.copy(kind = kind))
    }

    /** Trying again can't fix a password Ember can't decrypt, so the prepared copy goes too. */
    private suspend fun failLocked(upload: UploadEntity): Outcome {
        staging.delete(upload.stagedPath)
        return fail(upload.copy(stagedPath = null, sealedPassword = null), Failure(FailureKind.OTHER, context.getString(R.string.error_password_locked)))
    }

    private fun sourceFor(upload: UploadEntity): UploadSource {
        upload.stagedPath?.let { path ->
            val file = File(path)
            if (!file.exists()) throw FileNotFoundException("The prepared copy of ${upload.fileName} is gone.")
            return FileUploadSource(file, upload.fileName, upload.mimeType)
        }
        val uri = upload.sourceUri?.toUri() ?: throw FileNotFoundException("Nothing to upload for ${upload.fileName}.")
        return ContentUploadSource(context.contentResolver, uri, upload.fileName, upload.mimeType, upload.sizeBytes)
    }

    companion object {
        const val MAX_ATTEMPTS = 8

        /** Sync is less hurried than a share someone is waiting on. */
        const val SYNC_MAX_ATTEMPTS = 10
        private const val PROGRESS_INTERVAL_MILLIS = 500L
    }
}
