package dev.fl1nt.ember.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/**
 * Links for a finished upload, as returned by `POST /api/files` and chunked
 * upload completion. Flare builds them from its public `NEXTAUTH_URL`, which can
 * differ from the address Ember uses to reach the server.
 */
@Serializable
data class UploadedFile(
    val url: String? = null,
    val pageUrl: String? = null,
    val rawUrl: String? = null,
    val downloadUrl: String? = null,
    val copyText: String? = null,
    val name: String,
    /** Size in bytes. */
    val size: Long = 0,
    val type: String = "",
) {
    /** The share page. `url` and `copyText` are compatibility aliases for it. */
    val shareUrl: String
        get() = pageUrl ?: url ?: copyText ?: throw IllegalStateException("Flare returned no link for $name")

    internal fun hasLink(): Boolean = pageUrl != null || url != null || copyText != null
}

/** A file in the account's library, from `GET /api/files`. */
@Serializable
data class RemoteFile(
    val id: String,
    val name: String,
    /** The share page path, such as `/alex/report.pdf`. */
    val urlPath: String,
    val mimeType: String,
    /** Flare's file list reports size in MiB, unlike upload responses. */
    @SerialName("size") val sizeMiB: Double = 0.0,
    val uploadedAt: String,
    val visibility: String = "PUBLIC",
    val views: Long = 0,
    val downloads: Long = 0,
    val folderId: String? = null,
    val tags: List<RemoteTag> = emptyList(),
    val hasPassword: Boolean = false,
    val expiresAt: String? = null,
) {
    val sizeBytes: Long get() = (sizeMiB * 1_048_576).roundToLong()

    val isPrivate: Boolean get() = visibility.equals("PRIVATE", ignoreCase = true)

    /** Whether anyone with the link can open it without a password. */
    val isOpenlyShared: Boolean get() = !isPrivate && !hasPassword
}

@Serializable
data class RemoteTag(val id: String, val name: String)

@Serializable
data class Pagination(
    val total: Int = 0,
    val pageCount: Int = 0,
    val page: Int = 1,
    val limit: Int = 24,
)

data class FilePage(val files: List<RemoteFile>, val pagination: Pagination)

/** An account-owned short link. Its public address is `{origin}/u/{shortCode}`. */
@Serializable
data class ShortLink(
    val id: String,
    val shortCode: String,
    val targetUrl: String,
    val clicks: Long = 0,
    val createdAt: String,
)

/** A library folder. Folders nest through [parentId]. */
@Serializable
data class RemoteFolder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val fileCount: Int = 0,
)

/** How a file list is ordered. Values match Flare's `sortBy` parameter. */
enum class FileSort(val wireValue: String) {
    NEWEST("newest"),
    OLDEST("oldest"),
    LARGEST("largest"),
    SMALLEST("smallest"),
    NAME("name"),
    MOST_VIEWED("most-viewed"),
}

data class FileQuery(
    val page: Int = 1,
    val limit: Int = 24,
    val search: String? = null,
    val sort: FileSort = FileSort.NEWEST,
    val types: List<String> = emptyList(),
    val folderId: String? = null,
) {
    init {
        require(page >= 1) { "Pages start at 1" }
        require(limit in 1..100) { "Flare returns at most 100 files per page" }
    }
}

@Serializable
internal data class DataEnvelope<T>(val data: T)

@Serializable
internal data class FileListEnvelope(val data: List<RemoteFile>, val pagination: Pagination = Pagination())

@Serializable
internal data class ShortLinkList(val urls: List<ShortLink> = emptyList())

@Serializable
internal data class ErrorBody(val error: String? = null, val message: String? = null)

@Serializable
internal data class HealthBody(val status: String? = null)

@Serializable
internal data class CreateShortLinkRequest(val url: String)

@Serializable
internal data class ChunkSession(val uploadId: String, val fileKey: String? = null)

@Serializable
internal data class UploadedPart(val etag: String)

@Serializable
internal data class CompletedPart(
    @SerialName("PartNumber") val partNumber: Int,
    @SerialName("ETag") val etag: String,
)

@Serializable
internal data class CompleteRequest(val parts: List<CompletedPart>)

@Serializable
internal data class ChunkInitRequest(
    val filename: String,
    val mimeType: String,
    val size: Long,
    val visibility: String? = null,
    val password: String? = null,
    val expiration: String? = null,
    val expiryAction: String? = null,
)
