package dev.fl1nt.ember.data.sync

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import dev.fl1nt.ember.data.db.SourceType
import dev.fl1nt.ember.data.db.SyncSourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Lists the files currently in a sync source. */
fun interface SourceScanner {
    /** @throws SecurityException when Ember lost access to the source. */
    suspend fun scan(source: SyncSourceEntity): List<SourceItem>
}

/** Routes each source to the scanner for its type. */
class DefaultSourceScanner(
    private val media: SourceScanner,
    private val folders: SourceScanner,
) : SourceScanner {
    override suspend fun scan(source: SyncSourceEntity): List<SourceItem> =
        if (source.type == SourceType.MEDIA_ALBUM) media.scan(source) else folders.scan(source)
}

/**
 * Reads a photo and video album from MediaStore. Android hides files that are
 * still being written, and removes location data from photos unless the app
 * holds "Access media location", which Ember doesn't request.
 */
class MediaStoreScanner(private val resolver: ContentResolver) : SourceScanner {

    override suspend fun scan(source: SyncSourceEntity): List<SourceItem> = withContext(Dispatchers.IO) {
        val bucketId = source.bucketId ?: return@withContext emptyList()
        val mediaTypes = buildList {
            if (source.includeImages) add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            if (source.includeVideos) add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
        }
        if (mediaTypes.isEmpty()) return@withContext emptyList()
        val selection = buildString {
            append("${MediaStore.Images.ImageColumns.BUCKET_ID} = ?")
            append(" AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${mediaTypes.joinToString(",")})")
            if (source.sinceMillis > 0) append(" AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?")
        }
        val args = buildList {
            add(bucketId)
            if (source.sinceMillis > 0) add((source.sinceMillis / 1000).toString())
        }.toTypedArray()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val items = ArrayList<SourceItem>()
        resolver.query(FILES, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} ASC")?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val added = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val type = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (cursor.moveToNext()) {
                coroutineContext.ensureActive()
                val rowId = cursor.getLong(id)
                val collection = if (cursor.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) VIDEOS else IMAGES
                val addedSeconds = cursor.getLong(added)
                val modifiedSeconds = if (cursor.isNull(modified)) addedSeconds else cursor.getLong(modified)
                items += SourceItem(
                    key = rowId.toString(),
                    name = cursor.getString(name) ?: "media-$rowId",
                    mimeType = cursor.getString(mime),
                    size = if (cursor.isNull(size)) null else cursor.getLong(size),
                    uri = ContentUris.withAppendedId(collection, rowId).toString(),
                    addedAtMillis = addedSeconds * 1000,
                    modifiedAtMillis = maxOf(addedSeconds, modifiedSeconds) * 1000,
                )
            }
        }
        items
    }

    companion object {
        val FILES: Uri = MediaStore.Files.getContentUri("external")
        val IMAGES: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val VIDEOS: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
}

/** Walks a folder chosen through the system picker. */
class DocumentTreeScanner(private val resolver: ContentResolver) : SourceScanner {

    override suspend fun scan(source: SyncSourceEntity): List<SourceItem> = withContext(Dispatchers.IO) {
        val tree = (source.treeUri ?: return@withContext emptyList()).toUri()
        val permitted = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (!permitted) throw SecurityException("Ember no longer has access to ${source.name}")
        val items = ArrayList<SourceItem>()
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.addLast(DocumentsContract.getTreeDocumentId(tree) to 0)
        while (pending.isNotEmpty() && items.size < MAX_ITEMS) {
            coroutineContext.ensureActive()
            val (parent, depth) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            resolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (source.includeSubfolders && depth < MAX_DEPTH && !name.startsWith(".")) pending.addLast(documentId to depth + 1)
                        continue
                    }
                    val modified = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                    items += SourceItem(
                        key = documentId,
                        name = name,
                        mimeType = mime,
                        size = if (cursor.isNull(3)) null else cursor.getLong(3),
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString(),
                        addedAtMillis = modified,
                        modifiedAtMillis = modified,
                    )
                }
            }
        }
        items
    }

    private companion object {
        const val MAX_DEPTH = 12
        const val MAX_ITEMS = 50_000
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** A photo and video album, as Google Photos lists device folders. */
data class MediaAlbum(
    val bucketId: String,
    val name: String,
    val location: String?,
    val count: Int,
    val coverUri: Uri?,
    val latestAddedMillis: Long,
) {
    val isScreenshots: Boolean get() = name.equals("Screenshots", ignoreCase = true) || location?.contains("Screenshots", true) == true
    val isCamera: Boolean get() = name.equals("Camera", ignoreCase = true) || location?.startsWith("DCIM/Camera") == true
}

class MediaAlbums(private val resolver: ContentResolver) {

    suspend fun list(): List<MediaAlbum> = withContext(Dispatchers.IO) {
        val locationColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.MediaColumns.DATA
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Images.ImageColumns.BUCKET_ID,
            MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            locationColumn,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN " +
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val albums = LinkedHashMap<String, MediaAlbum>()
        resolver.query(MediaStoreScanner.FILES, projection, selection, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val bucket = cursor.getString(1) ?: continue
                val existing = albums[bucket]
                if (existing != null) {
                    albums[bucket] = existing.copy(count = existing.count + 1)
                    continue
                }
                val id = cursor.getLong(0)
                val collection = if (cursor.getInt(3) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                    MediaStoreScanner.VIDEOS
                } else {
                    MediaStoreScanner.IMAGES
                }
                albums[bucket] = MediaAlbum(
                    bucketId = bucket,
                    name = cursor.getString(2) ?: bucket,
                    location = cursor.getString(5)?.let(::readableLocation),
                    count = 1,
                    coverUri = ContentUris.withAppendedId(collection, id),
                    latestAddedMillis = cursor.getLong(4) * 1000,
                )
            }
        }
        albums.values.sortedWith(compareByDescending<MediaAlbum> { it.isCamera || it.isScreenshots }.thenByDescending { it.latestAddedMillis })
    }

    /** `DCIM/Camera/` or `/storage/emulated/0/DCIM/Camera/IMG.jpg` both become `DCIM/Camera`. */
    private fun readableLocation(value: String): String {
        val path = if (value.startsWith("/")) value.substringBeforeLast('/').substringAfter("/0/", value) else value
        return path.trim('/')
    }
}
