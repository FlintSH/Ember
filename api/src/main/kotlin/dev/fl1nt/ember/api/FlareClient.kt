package dev.fl1nt.ember.api

import dev.fl1nt.ember.api.internal.PartBody
import dev.fl1nt.ember.api.internal.StreamingBody
import dev.fl1nt.ember.api.internal.await
import dev.fl1nt.ember.api.internal.readFully
import dev.fl1nt.ember.api.internal.readUpTo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Talks to one Flare instance with one credential.
 *
 * Every function suspends and throws [FlareException] on failure. The client
 * never retries a request that could create a duplicate file; callers decide
 * when to try an upload again.
 */
class FlareClient(
    val origin: FlareOrigin,
    private val credential: FlareCredential,
    private val http: OkHttpClient,
    private val limiter: UploadStartLimiter = UploadStartLimiter(),
    private val partSizeBytes: Int = DEFAULT_PART_SIZE,
    private val chunkThresholdBytes: Long = DEFAULT_CHUNK_THRESHOLD,
) {
    init {
        require(partSizeBytes in MIN_PART_SIZE..MAX_PART_SIZE) { "Part size must be 5–64 MiB" }
        require(chunkThresholdBytes >= partSizeBytes) { "The chunk threshold must be at least one part" }
    }

    val credentialKind: FlareCredential.Kind get() = credential.kind

    /** Confirms the address belongs to a Flare server. Needs no credential. */
    suspend fun checkHealth() {
        val response = execute(request("/api/health").get().build(), authenticated = false)
        val body = response.bodyText()
        if (!response.isSuccessful) throw unexpected("The server answered HTTP ${response.code} instead of Flare's health check.")
        val health = try {
            json.decodeFromString(DataEnvelope.serializer(HealthBody.serializer()), body).data
        } catch (error: SerializationException) {
            throw unexpected("This address answered, but not like a Flare server.", error)
        } catch (error: IllegalArgumentException) {
            throw unexpected("This address answered, but not like a Flare server.", error)
        }
        if (health.status != "ok") throw unexpected("Flare reported that it isn't healthy.")
    }

    /** Discovers what this credential may do. Nothing is created or changed. */
    suspend fun probeCapabilities(): Capabilities {
        checkHealth()
        val capabilities = coroutineScope {
            val upload = async { probe(request("/api/files/chunks?uploadId=$PROBE_UPLOAD_ID&partNumber=1").get()) }
            val readFiles = async { probe(request("/api/files?limit=1").get()) }
            val readLinks = async { probe(request("/api/urls").get()) }
            val manageLinks = async { probe(request("/api/urls/$PROBE_LINK_ID").delete()) }
            val folders = async { probe(request("/api/folders").get()) }
            Capabilities(
                uploadFiles = upload.await(),
                readFiles = readFiles.await(),
                readLinks = readLinks.await(),
                manageLinks = manageLinks.await(),
                listFolders = folders.await(),
            )
        }
        if (capabilities.rejectedEverywhere) throw FlareException.Unauthorized()
        return capabilities
    }

    /**
     * Uploads one file. Large files with a known size go through Flare's chunked
     * upload API in [partSizeBytes] parts, which survives brief connection drops.
     * Pastes always use a single request because only it carries the paste flag.
     */
    suspend fun upload(
        source: UploadSource,
        options: UploadOptions = UploadOptions(),
        kind: UploadKind = UploadKind.FILE,
        progress: ProgressListener? = null,
    ): UploadedFile {
        val size = source.size
        val stream = BufferedInputStream(openSource(source), STREAM_BUFFER_BYTES)
        return try {
            stream.mark(MimeTypes.HEADER_BYTES)
            val header = stream.readUpTo(MimeTypes.HEADER_BYTES)
            stream.reset()
            val mimeType = if (kind == UploadKind.PASTE) {
                PASTE_MIME_TYPE
            } else {
                MimeTypes.resolve(source.declaredMimeType, header, source.fileName)
            }
            if (kind == UploadKind.FILE && size != null && size >= chunkThresholdBytes) {
                uploadInParts(stream, source.fileName, mimeType, size, options, progress)
            } else {
                uploadSingle(stream, source.fileName, mimeType, size ?: -1L, options, kind, progress)
            }
        } finally {
            stream.close()
        }
    }

    suspend fun listFiles(query: FileQuery = FileQuery()): FilePage {
        val url = origin.resolve("/api/files").newBuilder().apply {
            addQueryParameter("page", query.page.toString())
            addQueryParameter("limit", query.limit.toString())
            addQueryParameter("sortBy", query.sort.wireValue)
            query.search?.takeIf { it.isNotBlank() }?.let { addQueryParameter("search", it.trim()) }
            if (query.types.isNotEmpty()) addQueryParameter("types", query.types.joinToString(","))
            query.folderId?.let { addQueryParameter("folder", it) }
        }.build()
        val envelope = send(Request.Builder().url(url).get(), FileListEnvelope.serializer())
        return FilePage(envelope.data, envelope.pagination)
    }

    suspend fun createShortLink(target: String): ShortLink {
        val body = json.encodeToString(CreateShortLinkRequest.serializer(), CreateShortLinkRequest(target))
        return send(request("/api/urls").post(body.toRequestBody(JSON)), DataEnvelope.serializer(ShortLink.serializer())).data
    }

    suspend fun listShortLinks(): List<ShortLink> =
        send(request("/api/urls").get(), DataEnvelope.serializer(ShortLinkList.serializer())).data.urls

    /** Deletes a short link by its record ID. An already deleted link counts as success. */
    suspend fun deleteShortLink(id: String) {
        require(id.matches(ROUTE_ID)) { "Unexpected short link ID" }
        val response = execute(request("/api/urls/$id").delete().build())
        response.use {
            if (it.isSuccessful || it.code == 404) return
            throw it.toFlareException()
        }
    }

    /** Lists library folders. Flare only allows this for the account upload token. */
    suspend fun listFolders(): List<RemoteFolder> =
        send(request("/api/folders").get(), DataEnvelope.serializer(ListSerializer(RemoteFolder.serializer()))).data

    /** MIME types present in the library, for filters. */
    suspend fun fileTypes(): List<String> =
        send(request("/api/files/types").get(), DataEnvelope.serializer(FileTypes.serializer())).data.types

    /** A short link's public address. Flare's API only returns the code. */
    fun shortLinkUrl(link: ShortLink, publicOrigin: FlareOrigin = origin): String = "$publicOrigin/u/${link.shortCode}"

    /** The share page for a library file. */
    fun pageUrl(file: RemoteFile, publicOrigin: FlareOrigin = origin): String = "$publicOrigin${file.urlPath}"

    /**
     * A thumbnail address. It only loads for files anyone may open: tokens don't
     * authorize private thumbnails, which need a signed-in browser.
     */
    fun thumbnailUrl(file: RemoteFile): HttpUrl = origin.resolve("/api/files/${file.id}/thumbnail")

    private suspend fun uploadSingle(
        stream: InputStream,
        fileName: String,
        mimeType: String,
        size: Long,
        options: UploadOptions,
        kind: UploadKind,
        progress: ProgressListener?,
    ): UploadedFile {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            options.formFields().forEach { (name, value) -> addFormDataPart(name, value) }
            addFormDataPart("file", fileName, StreamingBody(stream, mimeType.toMediaType(), size, progress))
        }.build()
        val builder = request("/api/files").post(form)
        options.selectionHeaders().forEach { (name, value) -> builder.header(name, value) }
        if (kind == UploadKind.PASTE) builder.header("X-Flare-Paste", "true")
        limiter.acquire()
        return send(builder, DataEnvelope.serializer(UploadedFile.serializer())).data.requireLink()
    }

    private suspend fun uploadInParts(
        stream: InputStream,
        fileName: String,
        mimeType: String,
        size: Long,
        options: UploadOptions,
        progress: ProgressListener?,
    ): UploadedFile {
        val init = json.encodeToString(ChunkInitRequest.serializer(), options.chunkInit(fileName, mimeType, size))
        val start = request("/api/files/chunks").post(init.toRequestBody(JSON))
        options.selectionHeaders().forEach { (name, value) -> start.header(name, value) }
        limiter.acquire()
        val session = send(start, DataEnvelope.serializer(ChunkSession.serializer())).data
        require(session.uploadId.matches(ROUTE_ID)) { "Flare returned an unexpected upload ID" }

        val buffer = ByteArray(partSizeBytes)
        val parts = ArrayList<CompletedPart>()
        var sent = 0L
        progress?.onProgress(0, size)
        while (sent < size) {
            val count = minOf(partSizeBytes.toLong(), size - sent).toInt()
            stream.readFully(buffer, count)
            val partNumber = parts.size + 1
            val offset = sent
            val etag = withPartRetries {
                val body = PartBody(buffer, count, OCTET_STREAM) { written -> progress?.onProgress(offset + written, size) }
                val part = request("/api/files/chunks/${session.uploadId}/part/$partNumber").put(body)
                send(part, DataEnvelope.serializer(UploadedPart.serializer())).data.etag
            }
            parts += CompletedPart(partNumber, etag)
            sent += count
        }
        if (stream.read() != -1) throw FlareException.BadRequest("$fileName changed size while uploading. Try again.")

        val complete = json.encodeToString(CompleteRequest.serializer(), CompleteRequest(parts))
        // Completion is idempotent while the session exists, so a lost response is safe to repeat.
        return withPartRetries {
            send(
                request("/api/files/chunks/${session.uploadId}/complete").post(complete.toRequestBody(JSON)),
                UploadedFile.serializer(),
            ).requireLink()
        }
    }

    private suspend fun <T> withPartRetries(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: FlareException) {
                attempt++
                if (!error.isRetryable || attempt >= PART_ATTEMPTS) throw error
                val waitMillis = when (error) {
                    is FlareException.RateLimited -> error.retryAfterSeconds * 1000
                    else -> PART_RETRY_BASE_MILLIS * (1L shl (attempt - 1))
                }
                delay(waitMillis)
            }
        }
    }

    private suspend fun probe(builder: Request.Builder): Access = try {
        execute(builder.build()).use { response ->
            when (response.code) {
                in 200..299, 400, 404 -> Access.GRANTED
                401 -> Access.NOT_IN_TOKEN
                403 -> Access.DENIED_BY_ROLE
                else -> Access.UNKNOWN
            }
        }
    } catch (_: FlareException.Network) {
        Access.UNKNOWN
    }

    private suspend fun <T> send(builder: Request.Builder, serializer: KSerializer<T>): T {
        val response = execute(builder.build())
        response.use {
            if (!it.isSuccessful) throw it.toFlareException()
            val body = it.bodyText()
            return try {
                json.decodeFromString(serializer, body)
            } catch (error: SerializationException) {
                throw unexpected("Flare sent a response Ember couldn't read.", error)
            } catch (error: IllegalArgumentException) {
                throw unexpected("Flare sent a response Ember couldn't read.", error)
            }
        }
    }

    private suspend fun execute(request: Request, authenticated: Boolean = true): Response {
        val finalRequest = if (authenticated) {
            request.newBuilder().header("Authorization", credential.authorizationHeader).build()
        } else {
            request
        }
        val response = try {
            http.newCall(finalRequest).await()
        } catch (error: IOException) {
            throw FlareException.Network(error)
        }
        if (response.code == 429) {
            val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS
            limiter.pauseFor(retryAfter)
        }
        return response
    }

    private fun request(path: String): Request.Builder {
        val url = if (path.contains('?')) {
            val (route, query) = path.split('?', limit = 2)
            origin.resolve(route).newBuilder().encodedQuery(query).build()
        } else {
            origin.resolve(path)
        }
        return Request.Builder().url(url).header("Accept", "application/json")
    }

    private fun openSource(source: UploadSource): InputStream = try {
        source.open()
    } catch (error: IOException) {
        throw FlareException.Network(IOException("Couldn't read ${source.fileName}: ${error.message}", error))
    } catch (error: SecurityException) {
        throw FlareException.Forbidden("Ember lost permission to read ${source.fileName}.")
    }

    private fun Response.bodyText(): String = try {
        body.string()
    } catch (error: IOException) {
        throw FlareException.Network(error)
    }

    private fun Response.toFlareException(): FlareException {
        val text = try {
            body.string()
        } catch (_: IOException) {
            ""
        }
        val serverMessage = try {
            json.decodeFromString(ErrorBody.serializer(), text).let { it.error ?: it.message }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }?.takeIf { it.isNotBlank() }
        return when (code) {
            400 -> FlareException.BadRequest(serverMessage ?: "Flare couldn't accept this request.")
            401 -> FlareException.Unauthorized()
            403 -> FlareException.Forbidden(serverMessage ?: "Your Flare account isn't allowed to do this.")
            404 -> FlareException.NotFound(serverMessage ?: "Flare couldn't find that.")
            409 -> FlareException.Conflict(serverMessage ?: "Flare's storage changed during the upload. Try again.")
            413 -> FlareException.TooLarge(serverMessage ?: "The file is larger than this Flare instance accepts.")
            429 -> FlareException.RateLimited(header("Retry-After")?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS)
            in 500..599 -> FlareException.Server(code, serverMessage ?: "Flare had a problem (HTTP $code).")
            else -> unexpected("Flare answered with HTTP $code.")
        }
    }

    private fun UploadedFile.requireLink(): UploadedFile =
        if (hasLink()) this else throw unexpected("Flare finished the upload but returned no link.")

    private fun unexpected(message: String, cause: Throwable? = null) = FlareException.UnexpectedResponse(message, cause)

    @kotlinx.serialization.Serializable
    private data class FileTypes(val types: List<String> = emptyList())

    companion object {
        const val DEFAULT_PART_SIZE = 8 * 1024 * 1024
        const val DEFAULT_CHUNK_THRESHOLD = 24L * 1024 * 1024
        private const val MIN_PART_SIZE = 5 * 1024 * 1024
        private const val MAX_PART_SIZE = 64 * 1024 * 1024
        private const val PART_ATTEMPTS = 4
        private const val PART_RETRY_BASE_MILLIS = 1_000L
        private const val DEFAULT_RETRY_AFTER_SECONDS = 60L
        private const val STREAM_BUFFER_BYTES = 256 * 1024
        private const val PASTE_MIME_TYPE = "text/plain"

        // Well-formed IDs that can't exist, so the checks only reach authorization.
        private const val PROBE_UPLOAD_ID = "00000000000000000000000000000000"
        private const val PROBE_LINK_ID = "ember-capability-check"

        private val ROUTE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM = MimeTypes.OCTET_STREAM.toMediaType()

        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
