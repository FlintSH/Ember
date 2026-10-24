package dev.fl1nt.ember.api

import dev.fl1nt.ember.api.internal.PartBody
import dev.fl1nt.ember.api.internal.StreamingBody
import dev.fl1nt.ember.api.internal.await
import dev.fl1nt.ember.api.internal.fileDisposition
import dev.fl1nt.ember.api.internal.readAtMost
import dev.fl1nt.ember.api.internal.readInto
import dev.fl1nt.ember.api.internal.skipFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Talks to one Flare instance with one credential.
 *
 * Every function suspends and throws [FlareException] on failure. The client
 * never retries a request that could create a duplicate file; callers decide
 * when to try an upload again.
 *
 * Next.js, which Flare runs on, cuts request bodies off at 10 MiB before Flare
 * reads them, so files of [chunkThresholdBytes] or more upload in parts of
 * [partSizeBytes]. Neither may exceed 8 MiB, and a file that would need more than
 * Flare's 10,000 parts gets larger ones, up to that cap. A file of unknown size
 * that reaches the threshold is copied to [tempDirectory] (`java.io.tmpdir` when
 * `null`) first, because a chunked upload declares its size up front.
 */
class FlareClient(
    val origin: FlareOrigin,
    private val credential: FlareCredential,
    private val http: OkHttpClient,
    private val limiter: UploadStartLimiter = UploadStartLimiter(),
    private val partSizeBytes: Int = DEFAULT_PART_SIZE,
    private val chunkThresholdBytes: Long = DEFAULT_CHUNK_THRESHOLD,
    private val tempDirectory: File? = null,
) {
    init {
        require(partSizeBytes in MIN_PART_SIZE..MAX_PART_SIZE) { "Part size must be between 5 and 8 MiB" }
        require(chunkThresholdBytes in partSizeBytes..MAX_PART_SIZE) {
            "The chunk threshold must be at least one part and at most 8 MiB"
        }
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
     * Uploads one file.
     *
     * Files under [chunkThresholdBytes] go in one multipart request, larger ones
     * through Flare's chunked upload API, where a dropped connection only repeats
     * the part it interrupted. Pastes always use one request, because only it
     * carries the paste flag.
     *
     * A chunked upload can continue in a later call. [onChunkedProgress] receives
     * the state after the session starts and after each part, and the upload waits
     * for it, so save the state before returning; an exception from it stops the
     * upload. Pass the latest one back as [resume] to skip the parts Flare already
     * has. A state that doesn't match the file's size and part size is ignored. If
     * Flare no longer knows the session, Ember starts a new one, reports it, and
     * [progress] starts again from zero. Forget the state once the upload succeeds
     * or fails for good.
     */
    suspend fun upload(
        source: UploadSource,
        options: UploadOptions = UploadOptions(),
        kind: UploadKind = UploadKind.FILE,
        progress: ProgressListener? = null,
        resume: ChunkedProgress? = null,
        onChunkedProgress: (suspend (ChunkedProgress) -> Unit)? = null,
    ): UploadedFile {
        val stream = BufferedInputStream(openSource(source), STREAM_BUFFER_BYTES)
        return try {
            stream.mark(MimeTypes.HEADER_BYTES)
            val header = reading(source.fileName) { stream.readAtMost(MimeTypes.HEADER_BYTES) }
            reading(source.fileName) { stream.reset() }
            if (kind == UploadKind.PASTE) return uploadPaste(stream, source, options, progress)
            val mimeType = MimeTypes.resolve(source.declaredMimeType, header, source.fileName)
            val file = FileUpload(source.fileName, mimeType, options, progress, resume, onChunkedProgress)
            // Some providers report -1 for a size they don't know.
            val size = source.size?.takeIf { it >= 0 }
            when {
                size == null -> uploadOfUnknownSize(stream, file)
                size < chunkThresholdBytes -> uploadSingle(stream, source.fileName, mimeType, size, options, kind, progress)
                else -> uploadInParts(stream, size, file) { BufferedInputStream(openSource(source), STREAM_BUFFER_BYTES) }
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
            val disposition = Headers.headersOf("Content-Disposition", fileDisposition("file", fileName))
            addPart(disposition, StreamingBody(stream, mimeType.toMediaType(), size, progress))
        }.build()
        if (kind == UploadKind.PASTE && form.contentLength() > MAX_REQUEST_BYTES) {
            throw FlareException.TooLarge("Flare accepts pastes up to 10 MiB. Upload the text as a file instead.")
        }
        val builder = request("/api/files").post(form)
        options.selectionHeaders().forEach { (name, value) -> builder.header(name, value) }
        if (kind == UploadKind.PASTE) builder.header("X-Flare-Paste", "true")
        limiter.acquire()
        return send(builder, DataEnvelope.serializer(UploadedFile.serializer())).data.requireLink()
    }

    /** Only a single request carries the paste flag, so a paste has to fit in one. */
    private suspend fun uploadPaste(
        stream: InputStream,
        source: UploadSource,
        options: UploadOptions,
        progress: ProgressListener?,
    ): UploadedFile {
        val size = source.size?.takeIf { it >= 0 }
        if (size != null) return uploadSingle(stream, source.fileName, PASTE_MIME_TYPE, size, options, UploadKind.PASTE, progress)
        // One byte past the limit is enough to know a paste is too large.
        val text = reading(source.fileName) { stream.readAtMost(MAX_REQUEST_BYTES.toInt() + 1) }
        val bytes = ByteArrayInputStream(text)
        return uploadSingle(bytes, source.fileName, PASTE_MIME_TYPE, text.size.toLong(), options, UploadKind.PASTE, progress)
    }

    /** What [upload] was asked to send, passed along to whichever way it goes. */
    private class FileUpload(
        val fileName: String,
        val mimeType: String,
        val options: UploadOptions,
        val progress: ProgressListener?,
        val resume: ChunkedProgress?,
        val onChunkedProgress: (suspend (ChunkedProgress) -> Unit)?,
    )

    /**
     * A file under the threshold is read into memory and sent in one request.
     * Anything longer is copied to a temporary file, deleted afterwards, so the
     * chunked upload can declare its size and reopen it.
     */
    private suspend fun uploadOfUnknownSize(stream: InputStream, upload: FileUpload): UploadedFile {
        val copy = when (val measured = measure(stream, upload.fileName)) {
            is Measured.InMemory -> return uploadSingle(
                ByteArrayInputStream(measured.bytes),
                upload.fileName,
                upload.mimeType,
                measured.bytes.size.toLong(),
                upload.options,
                UploadKind.FILE,
                upload.progress,
            )
            is Measured.Copied -> measured.file
        }
        return try {
            val first = reading(upload.fileName) { FileInputStream(copy) }
            first.use { uploadInParts(it, copy.length(), upload) { FileInputStream(copy) } }
        } finally {
            copy.delete()
        }
    }

    private sealed interface Measured {
        class InMemory(val bytes: ByteArray) : Measured

        class Copied(val file: File) : Measured
    }

    private suspend fun measure(stream: InputStream, fileName: String): Measured {
        val head = reading(fileName) { stream.readAtMost(chunkThresholdBytes.toInt()) }
        if (head.size < chunkThresholdBytes) return Measured.InMemory(head)
        return Measured.Copied(copyToTemporaryFile(head, stream, fileName))
    }

    private suspend fun copyToTemporaryFile(head: ByteArray, rest: InputStream, fileName: String): File =
        withContext(Dispatchers.IO) {
            val file = try {
                File.createTempFile("ember-upload-", ".tmp", tempDirectory)
            } catch (error: IOException) {
                throw FlareException.Network(IOException("Couldn't prepare $fileName for upload: ${error.message}", error))
            }
            try {
                FileOutputStream(file).use { out ->
                    out.write(head)
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        ensureActive()
                        val read = rest.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                    }
                }
                file
            } catch (error: Throwable) {
                file.delete()
                if (error is IOException) {
                    throw FlareException.Network(IOException("Couldn't prepare $fileName for upload: ${error.message}", error))
                }
                throw error
            }
        }

    /**
     * Uploads [size] bytes from [first] through Flare's chunked upload API,
     * continuing the caller's saved state when it matches.
     *
     * Flare keeps a chunk session for one hour after its last activity: every
     * access to `lib/uploads/chunks.ts` checks `lastActivity`, and an hourly sweep
     * deletes stale sessions from the server's `tmp/uploads` directory, which a
     * redeployed server may also lose. Part uploads and completion then answer 404
     * "Upload not found.", and a session started before a storage change answers
     * 409. Neither can be finished, so Ember starts a new session from the first
     * part, at most once per call. If an earlier completion went through but its
     * response was lost and the session has expired since, that new upload stores
     * a second copy.
     */
    private suspend fun uploadInParts(
        first: InputStream,
        size: Long,
        upload: FileUpload,
        reopen: () -> InputStream,
    ): UploadedFile {
        val partSize = partSizeFor(size)
        val buffer = ByteArray(partSize)
        var session = upload.resume?.takeIf { it.continues(size, partSize) } ?: startSession(size, partSize, upload)
        var stream = first
        var startedOver = false
        try {
            while (true) {
                try {
                    return sendParts(stream, buffer, session, upload)
                } catch (error: FlareException) {
                    val sessionLost = error is FlareException.NotFound || error is FlareException.Conflict
                    if (!sessionLost || startedOver) throw error
                    startedOver = true
                }
                if (stream !== first) stream.close()
                stream = reading(upload.fileName) { reopen() }
                session = startSession(size, partSize, upload)
            }
        } finally {
            if (stream !== first) stream.close()
        }
    }

    private suspend fun startSession(size: Long, partSize: Int, upload: FileUpload): ChunkedProgress {
        val init = json.encodeToString(ChunkInitRequest.serializer(), upload.options.chunkInit(upload.fileName, upload.mimeType, size))
        val start = request("/api/files/chunks").post(init.toRequestBody(JSON))
        upload.options.selectionHeaders().forEach { (name, value) -> start.header(name, value) }
        limiter.acquire()
        val created = send(start, DataEnvelope.serializer(ChunkSession.serializer())).data
        if (!created.uploadId.matches(ROUTE_ID)) throw unexpected("Flare returned an unexpected upload ID.")
        return ChunkedProgress(created.uploadId, partSize, size).also { upload.onChunkedProgress?.invoke(it) }
    }

    /** Sends the parts [start] lacks, reading [stream] from its first byte, then completes the upload. */
    private suspend fun sendParts(
        stream: InputStream,
        buffer: ByteArray,
        start: ChunkedProgress,
        upload: FileUpload,
    ): UploadedFile {
        val size = start.totalSize
        val fileName = upload.fileName
        var session = start
        var sent = session.uploadedBytes
        if (sent > 0 && !reading(fileName) { stream.skipFully(sent) }) throw endedEarly(fileName)
        upload.progress?.onProgress(sent, size)
        while (sent < size) {
            val count = minOf(session.partSize.toLong(), size - sent).toInt()
            if (reading(fileName) { stream.readInto(buffer, count) } < count) throw endedEarly(fileName)
            val partNumber = session.parts.size + 1
            val offset = sent
            val etag = try {
                withPartRetries {
                    val body = PartBody(buffer, count, OCTET_STREAM) { written -> upload.progress?.onProgress(offset + written, size) }
                    val part = request("/api/files/chunks/${session.uploadId}/part/$partNumber").put(body)
                    send(part, DataEnvelope.serializer(UploadedPart.serializer())).data.etag
                }
            } catch (conflict: FlareException.Conflict) {
                // A part sent after completion gets 409 too. If the saved state lagged behind a
                // finished upload, completing returns that file instead of storing a second one.
                if (session.parts.isEmpty()) throw conflict
                return try {
                    complete(session)
                } catch (unusable: FlareException) {
                    val sessionLost = unusable is FlareException.Conflict || unusable is FlareException.NotFound ||
                        unusable is FlareException.BadRequest
                    throw if (sessionLost) conflict else unusable
                }
            }
            session = session.copy(parts = session.parts + CompletedPart(partNumber, etag))
            sent += count
            upload.onChunkedProgress?.invoke(session)
        }
        if (reading(fileName) { stream.read() } != -1) throw FlareException.BadRequest("$fileName changed size while uploading. Try again.")
        return complete(session)
    }

    /**
     * Completion is idempotent while the session exists: Flare returns the stored
     * file before assembling anything, so a lost response is safe to repeat.
     */
    private suspend fun complete(session: ChunkedProgress): UploadedFile {
        val complete = json.encodeToString(CompleteRequest.serializer(), CompleteRequest(session.parts))
        return withPartRetries {
            send(
                request("/api/files/chunks/${session.uploadId}/complete").post(complete.toRequestBody(JSON)),
                UploadedFile.serializer(),
            ).requireLink()
        }
    }

    /** Flare numbers parts from 1 to 10,000, so a file too large for that many parts needs larger ones. */
    private fun partSizeFor(size: Long): Int {
        val needed = (size + MAX_PARTS - 1) / MAX_PARTS
        if (needed > MAX_PART_SIZE) {
            throw FlareException.TooLarge(
                "Flare's chunked uploads hold 10,000 parts of at most 8 MiB, so files over 78 GiB can't be uploaded.",
            )
        }
        return maxOf(partSizeBytes.toLong(), needed).toInt()
    }

    /** Whether a saved state belongs to this file: same size and part size, and parts 1 to n with ETags. */
    private fun ChunkedProgress.continues(size: Long, partSize: Int): Boolean =
        totalSize == size &&
            this.partSize == partSize &&
            uploadId.matches(ROUTE_ID) &&
            parts.size <= (size + partSize - 1) / partSize &&
            parts.withIndex().all { (index, part) -> part.partNumber == index + 1 && part.etag.isNotEmpty() }

    private fun endedEarly(fileName: String) =
        FlareException.Network(IOException("$fileName is shorter than its reported size. It may have changed while uploading."))

    /** Reading the source can fail at any point; that is worth another try, like a dropped connection. */
    private inline fun <T> reading(fileName: String, block: () -> T): T = try {
        block()
    } catch (error: IOException) {
        throw FlareException.Network(IOException("Couldn't read $fileName: ${error.message}", error))
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

    /**
     * Network failures, outages, and rate limits propagate, so a temporary problem
     * isn't mistaken for a missing scope.
     */
    private suspend fun probe(builder: Request.Builder): Access =
        execute(builder.build()).use { response ->
            when (response.code) {
                in 200..299, 400, 404 -> Access.GRANTED
                401 -> Access.NOT_IN_TOKEN
                403 -> Access.DENIED_BY_ROLE
                408, 425, 429, in 500..599 -> throw response.toFlareException()
                else -> Access.UNKNOWN
            }
        }

    private suspend fun <T> send(builder: Request.Builder, serializer: KSerializer<T>): T {
        val response = execute(builder.build())
        response.use {
            if (!it.isSuccessful) throw it.toFlareException()
            val body = it.bodyText()
            return try {
                json.decodeFromString(serializer, body)
            } catch (error: SerializationException) {
                throw unreadable(it, error)
            } catch (error: IllegalArgumentException) {
                throw unreadable(it, error)
            }
        }
    }

    /**
     * Flare's API always answers with JSON. Anything else came from something in
     * between, such as a Wi-Fi sign-in page, so the request never reached Flare
     * and is safe to repeat. Unreadable JSON did come from Flare, which may have
     * stored the file, so repeating it could upload a duplicate.
     */
    private fun unreadable(response: Response, cause: Throwable): FlareException =
        if (response.body.contentType()?.subtype?.contains("json") == true) {
            unexpected("Flare sent a response Ember couldn't read.", cause)
        } else {
            FlareException.Network(IOException("Something other than Flare answered. If this network has a sign-in page, sign in first.", cause))
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
    } catch (error: FileNotFoundException) {
        throw FlareException.SourceUnavailable("${source.fileName} is no longer available.", error)
    } catch (error: SecurityException) {
        throw FlareException.SourceUnavailable("Ember lost permission to read ${source.fileName}.", error)
    } catch (error: IOException) {
        throw FlareException.Network(IOException("Couldn't read ${source.fileName}: ${error.message}", error))
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
            408, 425 -> FlareException.Server(code, serverMessage ?: "Flare took too long to answer (HTTP $code).")
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

        /** Files of this many bytes or more upload in parts. */
        const val DEFAULT_CHUNK_THRESHOLD = 8L * 1024 * 1024

        // S3 needs parts of at least 5 MiB, except the last.
        private const val MIN_PART_SIZE = 5 * 1024 * 1024

        // Next.js truncates larger bodies before Flare sees them. A part over 10 MiB
        // is stored short without an error and only fails at completion.
        private const val MAX_PART_SIZE = 8 * 1024 * 1024
        private const val MAX_REQUEST_BYTES = 10L * 1024 * 1024
        private const val MAX_PARTS = 10_000
        private const val COPY_BUFFER_BYTES = 256 * 1024
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
