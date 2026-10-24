package dev.fl1nt.ember.api

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Something Ember can upload: a local file, a content URI, or text. */
interface UploadSource {
    /** The name Flare should store. Flare picks the final URL name itself. */
    val fileName: String

    /** The type a content provider reported, if any. Ember verifies it before use. */
    val declaredMimeType: String?

    /** The exact size in bytes, or `null` when the provider doesn't report one. */
    val size: Long?

    /** Opens a new stream at the first byte. Ember may call this more than once. */
    fun open(): InputStream
}

class BytesUploadSource(
    override val fileName: String,
    private val bytes: ByteArray,
    override val declaredMimeType: String? = null,
) : UploadSource {
    override val size: Long get() = bytes.size.toLong()

    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

class FileUploadSource(
    private val file: File,
    override val fileName: String = file.name,
    override val declaredMimeType: String? = null,
) : UploadSource {
    override val size: Long get() = file.length()

    override fun open(): InputStream = FileInputStream(file)
}

/** What kind of item an upload creates in Flare. */
enum class UploadKind {
    FILE,

    /** A text paste. Requires the `pastes.create` role permission. */
    PASTE,
}

/** Receives transferred and total byte counts. `total` is `-1` when unknown. */
fun interface ProgressListener {
    fun onProgress(sentBytes: Long, totalBytes: Long)
}
