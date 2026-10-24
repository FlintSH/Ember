package dev.fl1nt.ember.api.internal

import dev.fl1nt.ember.api.ProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Runs the call without blocking, and cancels it when the coroutine is cancelled. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, _, _ -> response.close() }
            }
        },
    )
}

/**
 * Streams an already opened [InputStream] and reports progress.
 *
 * It is one-shot, so OkHttp never silently repeats it. Repeating a multipart
 * upload whose response was lost can create a second file in Flare.
 */
internal class StreamingBody(
    private val stream: InputStream,
    private val mediaType: MediaType,
    private val length: Long,
    private val listener: ProgressListener?,
) : RequestBody() {
    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = length

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val source = stream.source()
        val buffer = Buffer()
        var sent = 0L
        listener?.onProgress(0, length)
        while (true) {
            val read = source.read(buffer, SEGMENT_BYTES)
            if (read == -1L) break
            sink.write(buffer, read)
            sent += read
            listener?.onProgress(sent, length)
        }
    }

    private companion object {
        const val SEGMENT_BYTES = 64L * 1024
    }
}

/** A repeatable body over part of a byte array, used for chunk retries. */
internal class PartBody(
    private val bytes: ByteArray,
    private val count: Int,
    private val mediaType: MediaType,
    private val onWritten: (Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = count.toLong()

    override fun writeTo(sink: BufferedSink) {
        var offset = 0
        while (offset < count) {
            val next = minOf(count - offset, 64 * 1024)
            sink.write(bytes, offset, next)
            offset += next
            onWritten(offset.toLong())
        }
    }
}

/** Fills [target] with up to [count] bytes and returns how many arrived before the stream ended. */
internal fun InputStream.readInto(target: ByteArray, count: Int): Int {
    var total = 0
    while (total < count) {
        val read = read(target, total, count - total)
        if (read == -1) break
        total += read
    }
    return total
}

/**
 * Reads up to [max] bytes without assuming one `read` call fills the buffer. The
 * buffer grows as data arrives, so a short stream doesn't allocate [max] bytes.
 */
internal fun InputStream.readAtMost(max: Int): ByteArray {
    var buffer = ByteArray(minOf(max, 64 * 1024))
    var total = 0
    while (total < max) {
        if (total == buffer.size) buffer = buffer.copyOf(minOf(max, buffer.size * 2))
        val read = read(buffer, total, buffer.size - total)
        if (read == -1) break
        total += read
    }
    return if (total == buffer.size) buffer else buffer.copyOf(total)
}

/** Skips exactly [count] bytes, or returns `false` when the stream ends first. */
internal fun InputStream.skipFully(count: Long): Boolean {
    if (count <= 0) return true
    var remaining = count - 1
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            // skip() may return 0 before the end; a read tells the two apart.
            if (read() == -1) return false
            remaining--
        }
    }
    // FileInputStream.skip can move past the end without saying so, so the last byte is read.
    return read() != -1
}
