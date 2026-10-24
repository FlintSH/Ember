package dev.fl1nt.ember.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Where a chunked upload stands, so a later attempt can continue it.
 *
 * [FlareClient.upload] reports a new value after the session starts and after
 * each part. Save the latest one with the upload, for example as [toJson], and
 * pass it back as `resume`. Flare forgets a session after an hour without a part,
 * so an old value makes Ember start over rather than fail.
 */
@Serializable
data class ChunkedProgress(
    val uploadId: String,
    /** Bytes in every part but the last. */
    val partSize: Int,
    /** The file's size in bytes. */
    val totalSize: Long,
    /** Parts Flare stored, in order from part 1. */
    val parts: List<CompletedPart> = emptyList(),
) {
    /** Bytes Flare already holds. */
    val uploadedBytes: Long get() = minOf(parts.size.toLong() * partSize, totalSize)

    fun toJson(): String = FlareClient.json.encodeToString(serializer(), this)

    companion object {
        /** Reads [toJson]'s output. Anything else gives `null`, so the upload starts over. */
        fun fromJson(json: String): ChunkedProgress? = try {
            FlareClient.json.decodeFromString(serializer(), json)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** A part Flare stored, with the ETag that completing the upload needs. */
@Serializable
data class CompletedPart(
    @SerialName("PartNumber") val partNumber: Int,
    @SerialName("ETag") val etag: String,
)
