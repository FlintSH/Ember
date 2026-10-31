package dev.fl1nt.ember.data.sync

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import dev.fl1nt.ember.data.db.EmberDatabase
import dev.fl1nt.ember.data.db.LedgerCounts
import dev.fl1nt.ember.data.db.SourceType
import dev.fl1nt.ember.data.db.SyncSourceEntity
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.data.settings.SettingsStore
import dev.fl1nt.ember.data.settings.UploadPreset
import dev.fl1nt.ember.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A folder Ember can add, before it's saved. */
sealed interface NewSource {
    val name: String

    data class Album(val album: MediaAlbum) : NewSource {
        override val name: String get() = album.name
    }

    data class Folder(val treeUri: Uri, override val name: String, val location: String?) : NewSource
}

class SyncRepository(
    private val db: EmberDatabase,
    private val resolver: ContentResolver,
    private val albums: MediaAlbums,
    private val settings: SettingsStore,
    private val scheduler: WorkScheduler,
    /** Counts what's waiting in one folder again without uploading: [SyncEngine.recount]. */
    private val recount: suspend (Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeSources(): Flow<List<SyncSourceEntity>> = db.sources().observeAll()

    fun observeSource(id: Long): Flow<SyncSourceEntity?> = db.sources().observe(id)

    fun observeCounts(id: Long): Flow<LedgerCounts> = db.ledger().observeCounts(id).map { it ?: LedgerCounts(0, 0) }

    fun observeRecentUploads(id: Long): Flow<List<UploadEntity>> = db.uploads().observeForSource(id, 30)

    suspend fun listAlbums(): List<MediaAlbum> = albums.list()

    /** Describes a folder returned by the system picker. */
    suspend fun describeFolder(treeUri: Uri): NewSource.Folder = withContext(Dispatchers.IO) {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val name = runCatching {
            resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: documentId.substringAfterLast('/').substringAfterLast(':')
        // External storage IDs look like "primary:Pictures/Screenshots".
        val location = documentId.substringAfter(':', "").takeIf { it.isNotEmpty() && treeUri.authority == EXTERNAL_STORAGE }
        NewSource.Folder(treeUri, name.ifEmpty { "Folder" }, location)
    }

    suspend fun add(
        source: NewSource,
        uploadExisting: Boolean,
        preset: UploadPreset,
        copyLink: Boolean,
        notifyEach: Boolean,
    ): Long {
        val now = clock()
        val entity = when (source) {
            is NewSource.Album -> SyncSourceEntity(
                type = SourceType.MEDIA_ALBUM,
                name = source.album.name,
                bucketId = source.album.bucketId,
                location = source.album.location,
                sinceMillis = if (uploadExisting) 0 else now,
                presetJson = preset.encode(),
                copyLink = copyLink,
                notifyEach = notifyEach,
                createdAt = now,
            )
            is NewSource.Folder -> {
                resolver.takePersistableUriPermission(source.treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                SyncSourceEntity(
                    type = SourceType.FOLDER,
                    name = source.name,
                    location = source.location,
                    treeUri = source.treeUri.toString(),
                    sinceMillis = if (uploadExisting) 0 else now,
                    presetJson = preset.encode(),
                    copyLink = copyLink,
                    notifyEach = notifyEach,
                    createdAt = now,
                )
            }
        }
        val id = db.sources().insert(entity)
        reschedule(force = true)
        scheduler.syncNow(userInitiated = true)
        return id
    }

    suspend fun update(source: SyncSourceEntity) {
        db.sources().update(source)
        reschedule(force = true)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val source = db.sources().get(id) ?: return
        db.sources().update(source.copy(enabled = enabled))
        reschedule(force = false)
        if (enabled) scheduler.syncNow(userInitiated = true)
    }

    /**
     * Switches a folder between everything in it and new files only, counted from
     * when it was added. It only changes what happens to files Ember hasn't started:
     * the ledger stays, so nothing that uploaded is sent again, and a file waiting to
     * retry or resume still finishes.
     */
    suspend fun setUploadExisting(id: Long, uploadExisting: Boolean) {
        val source = db.sources().get(id) ?: return
        val since = if (uploadExisting) 0 else source.createdAt
        if (source.sinceMillis == since) return
        db.sources().setSince(id, since)
        if (!source.enabled) return
        // Asking for the older files counts as Sync now. Going back uploads nothing new, so
        // counting again is enough, and it works while sync is off or waiting for Wi-Fi.
        if (uploadExisting) scheduler.syncNow(userInitiated = true) else recount(id)
    }

    /** Lets permanently failed files from this source upload again. */
    suspend fun retryFailures(id: Long) {
        db.ledger().clearFailures(id)
        // Back in the queue they count as started, so they retry even when they're older
        // than the start of a folder that went back to new files only.
        db.uploads().requeueFailedSync(id, clock())
        scheduler.syncNow(userInitiated = true)
    }

    /**
     * Forgets a folder. Uploaded files stay in history; files still waiting go,
     * because nothing would ever upload them. A sync that's running stops before
     * its next file from this folder.
     */
    suspend fun remove(id: Long) {
        val source = db.sources().get(id) ?: return
        db.sources().delete(id)
        db.ledger().deleteForSource(id)
        db.uploads().deleteUnfinishedForSource(id)
        source.treeUri?.let { uri ->
            val stillUsed = db.sources().all().any { it.treeUri == uri }
            if (!stillUsed) {
                runCatching { resolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
        reschedule(force = false)
    }

    suspend fun reschedule(force: Boolean) {
        val sources = db.sources().all().filter { it.enabled }
        scheduler.configureSync(
            prefs = settings.settings.first().sync,
            hasSources = sources.isNotEmpty(),
            hasMediaSources = sources.any { it.type == SourceType.MEDIA_ALBUM },
            force = force,
        )
    }

    private companion object {
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    }
}
