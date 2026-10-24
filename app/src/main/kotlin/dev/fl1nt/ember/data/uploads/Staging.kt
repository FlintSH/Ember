package dev.fl1nt.ember.data.uploads

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** A shared file, copied into Ember's private storage. */
data class StagedFile(val file: File, val displayName: String, val mimeType: String?, val size: Long)

class InsufficientSpaceException(val needed: Long) : IOException("Not enough free space to prepare the upload")

/**
 * Copies shared content before uploading it.
 *
 * Android revokes another app's temporary read grant once the share sheet
 * closes, so a background upload that started later, or retried after losing
 * signal, could no longer read the file. A private copy keeps every retry
 * possible. Ember deletes it once the upload finishes or is removed.
 */
class UploadStaging(
    private val context: Context,
    private val root: File = File(context.noBackupFilesDir, "staging"),
) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun stage(uri: Uri, uploadId: String): StagedFile = withContext(Dispatchers.IO) {
        val (name, reportedSize) = describe(uri)
        val mimeType = resolver.getType(uri)
        val displayName = safeName(name, mimeType)
        if (reportedSize != null && !hasRoomFor(reportedSize)) throw InsufficientSpaceException(reportedSize)
        val target = File(directory(uploadId), displayName)
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("Couldn't open $displayName")
        input.use { source ->
            target.outputStream().use { sink ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                }
            }
        }
        StagedFile(target, displayName, mimeType, target.length())
    }

    suspend fun stageText(text: String, fileName: String, uploadId: String): StagedFile = withContext(Dispatchers.IO) {
        val displayName = safeName(fileName, "text/plain")
        val target = File(directory(uploadId), displayName)
        target.writeText(text, Charsets.UTF_8)
        StagedFile(target, displayName, "text/plain", target.length())
    }

    /** Reads the display name and size a content provider reports. */
    fun describe(uri: Uri): Pair<String?, Long?> {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = File(uri.path ?: return null to null)
            return file.name to file.length()
        }
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null to null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size
            } ?: (null to null)
        } catch (_: SecurityException) {
            null to null
        } catch (_: IllegalArgumentException) {
            null to null
        }
    }

    /** Deletes a staged file and its per-upload folder. Ignores paths outside staging. */
    fun delete(path: String?) {
        val folder = path?.let(::File)?.canonicalFile?.parentFile ?: return
        if (folder.parentFile != root.canonicalFile) return
        folder.deleteRecursively()
    }

    /** Deletes everything staged for one upload. */
    fun discard(uploadId: String) {
        if (!uploadId.matches(UPLOAD_ID)) return
        File(root, uploadId).deleteRecursively()
    }

    /** Removes copies whose uploads no longer exist. */
    fun prune(keepUploadIds: Set<String>) {
        root.listFiles()?.filter { it.name !in keepUploadIds }?.forEach { it.deleteRecursively() }
    }

    fun hasRoomFor(bytes: Long): Boolean {
        root.mkdirs()
        val available = StatFs(root.path).availableBytes
        return available - bytes > SPACE_RESERVE
    }

    private fun directory(uploadId: String): File {
        require(uploadId.matches(UPLOAD_ID)) { "Unexpected upload id" }
        return File(root, uploadId).apply { mkdirs() }
    }

    companion object {
        private val UPLOAD_ID = Regex("^[A-Za-z0-9-]+$")
        private const val COPY_BUFFER = 256 * 1024

        // Leave room for the rest of the phone.
        private const val SPACE_RESERVE = 200L * 1024 * 1024

        /** A file name that is safe on disk and meaningful in Flare. */
        fun safeName(name: String?, mimeType: String?): String {
            val cleaned = name.orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filterNot { it.isISOControl() }
                .trim()
                .trimStart('.')
                .take(200)
            if (cleaned.isNotEmpty()) return cleaned
            val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            return if (extension != null) "shared-file.$extension" else "shared-file"
        }
    }
}
