package dev.fl1nt.ember.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.BufferedSource
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FlareClientTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(
        token: String = "flr_test-token",
        partSize: Int = FlareClient.DEFAULT_PART_SIZE,
        threshold: Long = FlareClient.DEFAULT_CHUNK_THRESHOLD,
        tempDirectory: File? = null,
    ) = FlareClient(
        origin = FlareOrigin.parse(server.url("/").toString()),
        credential = FlareCredential.of(token),
        http = http,
        partSizeBytes = partSize,
        chunkThresholdBytes = threshold,
        tempDirectory = tempDirectory,
    )

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse =
        MockResponse.Builder().code(code).body(body).apply {
            addHeader("Content-Type", "application/json")
            headers.forEach { (name, value) -> addHeader(name, value) }
        }.build()

    private val uploadResponse = """
        {"success":true,"data":{"url":"https://files.example.com/alex/a.png","pageUrl":"https://files.example.com/alex/a.png",
        "rawUrl":"https://files.example.com/api/files/abc/a.png","downloadUrl":"https://files.example.com/api/files/cm1/download",
        "copyText":"https://files.example.com/alex/a.png","name":"a.png","size":12,"type":"image/png"}}
    """.trimIndent()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)

    @Test
    fun `health check accepts Flare and rejects other servers`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"status":"ok"}}"""))
        client().checkHealth()
        assertThat(server.takeRequest().headers["Authorization"]).isNull()

        server.enqueue(MockResponse.Builder().code(200).body("<html>Welcome to nginx</html>").build())
        val error = runCatching { client().checkHealth() }.exceptionOrNull()
        assertThat(error).isInstanceOf(FlareException.UnexpectedResponse::class.java)
    }

    @Test
    fun `probes each capability without side effects`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath == "/api/health" -> json(200, """{"success":true,"data":{"status":"ok"}}""")
                request.url.encodedPath == "/api/files/chunks" -> json(404, """{"error":"Upload not found."}""")
                request.url.encodedPath == "/api/files" -> json(200, """{"success":true,"data":[],"pagination":{}}""")
                request.url.encodedPath == "/api/urls" -> json(401, """{"error":"Unauthorized"}""")
                request.method == "DELETE" -> json(403, """{"error":"You do not have permission to perform this action."}""")
                request.url.encodedPath == "/api/folders" -> json(401, """{"error":"Unauthorized"}""")
                else -> json(500, "{}")
            }
        }
        val capabilities = client().probeCapabilities()
        assertThat(capabilities).isEqualTo(
            Capabilities(
                uploadFiles = Access.GRANTED,
                readFiles = Access.GRANTED,
                readLinks = Access.NOT_IN_TOKEN,
                manageLinks = Access.DENIED_BY_ROLE,
                listFolders = Access.NOT_IN_TOKEN,
            ),
        )
        val methods = (1..server.requestCount).map { server.takeRequest() }.map { "${it.method} ${it.url.encodedPath}" }
        assertThat(methods).containsNoneOf("POST /api/files", "POST /api/urls", "POST /api/files/chunks")
    }

    @Test
    fun `a token rejected everywhere is unauthorized`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath == "/api/health") {
                    json(200, """{"success":true,"data":{"status":"ok"}}""")
                } else {
                    json(401, """{"error":"Unauthorized"}""")
                }
        }
        val error = runCatching { client().probeCapabilities() }.exceptionOrNull()
        assertThat(error).isInstanceOf(FlareException.Unauthorized::class.java)
    }

    @Test
    fun `uploads a file with options, selection headers and progress`() = runTest {
        server.enqueue(json(200, uploadResponse))
        val progress = mutableListOf<Long>()
        val result = client().upload(
            BytesUploadSource("a.png", png, declaredMimeType = "application/octet-stream"),
            UploadOptions(
                visibility = Visibility.PRIVATE,
                expiration = Expiration.DAY,
                profile = Selection.Id("profile_1"),
                folder = Selection.Id("folder_9"),
            ),
            progress = { sent, _ -> progress += sent },
        )
        assertThat(result.shareUrl).isEqualTo("https://files.example.com/alex/a.png")
        assertThat(result.size).isEqualTo(12)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/api/files")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer flr_test-token")
        assertThat(request.headers["X-Upload-Profile"]).isEqualTo("profile_1")
        assertThat(request.headers["X-Upload-Folder"]).isEqualTo("folder_9")
        assertThat(request.headers["X-Flare-Paste"]).isNull()
        assertThat(request.headers["Content-Type"]).startsWith("multipart/form-data; boundary=")
        val body = request.body!!.utf8()
        assertThat(body).contains("name=\"visibility\"\r\n\r\nPRIVATE")
        assertThat(body).contains("name=\"expiration\"\r\n\r\nDAY")
        assertThat(body).contains("name=\"file\"; filename=\"a.png\"\r\nContent-Type: image/png")
        assertThat(body.indexOf("name=\"visibility\"")).isLessThan(body.indexOf("name=\"file\""))
        assertThat(progress.last()).isEqualTo(png.size.toLong())
    }

    @Test
    fun `creates pastes as text with the paste flag`() = runTest {
        server.enqueue(json(200, uploadResponse))
        client().upload(BytesUploadSource("notes.md", "# hi".toByteArray()), kind = UploadKind.PASTE)
        val request = server.takeRequest()
        assertThat(request.headers["X-Flare-Paste"]).isEqualTo("true")
        assertThat(request.body!!.utf8()).contains("filename=\"notes.md\"\r\nContent-Type: text/plain")
    }

    @Test
    fun `uploads large files in parts and completes with every ETag`() = runTest {
        val partSize = 5 * 1024 * 1024
        val data = ByteArray(partSize * 2 + 1234) { (it % 251).toByte() }
        System.arraycopy(png, 0, data, 0, png.size)
        server.enqueue(json(200, """{"data":{"uploadId":"0123456789abcdef0123456789abcdef","fileKey":"uploads/x"}}"""))
        server.enqueue(json(200, """{"data":{"etag":"\"e1\""}}"""))
        server.enqueue(json(200, """{"data":{"etag":"\"e2\""}}"""))
        server.enqueue(json(200, """{"data":{"etag":"\"e3\""}}"""))
        server.enqueue(json(200, uploadResponse.substringAfter("\"data\":").removeSuffix("}")))

        val result = client(partSize = partSize, threshold = partSize.toLong()).upload(
            BytesUploadSource("big.png", data),
            UploadOptions(visibility = Visibility.PUBLIC, folder = Selection.Id("folder_1")),
        )
        assertThat(result.name).isEqualTo("a.png")

        val init = server.takeRequest()
        assertThat(init.url.encodedPath).isEqualTo("/api/files/chunks")
        assertThat(init.headers["X-Upload-Folder"]).isEqualTo("folder_1")
        assertThat(init.body!!.utf8()).isEqualTo(
            """{"filename":"big.png","mimeType":"image/png","size":${data.size},"visibility":"PUBLIC"}""",
        )
        val parts = (1..3).map { server.takeRequest() }
        assertThat(parts.map { it.url.encodedPath }).containsExactly(
            "/api/files/chunks/0123456789abcdef0123456789abcdef/part/1",
            "/api/files/chunks/0123456789abcdef0123456789abcdef/part/2",
            "/api/files/chunks/0123456789abcdef0123456789abcdef/part/3",
        ).inOrder()
        assertThat(parts.map { it.bodySize }).containsExactly(partSize.toLong(), partSize.toLong(), 1234L).inOrder()
        assertThat(parts.map { it.method }.distinct()).containsExactly("PUT")
        val complete = server.takeRequest()
        assertThat(complete.url.encodedPath).isEqualTo("/api/files/chunks/0123456789abcdef0123456789abcdef/complete")
        assertThat(complete.body!!.utf8()).isEqualTo(
            """{"parts":[{"PartNumber":1,"ETag":"\"e1\""},{"PartNumber":2,"ETag":"\"e2\""},{"PartNumber":3,"ETag":"\"e3\""}]}""",
        )
    }

    @Test
    fun `retries a failed part but never repeats the upload start`() = runTest {
        val partSize = 5 * 1024 * 1024
        val data = ByteArray(partSize) { 1 }
        server.enqueue(json(200, """{"data":{"uploadId":"abc","fileKey":"k"}}"""))
        server.enqueue(json(503, """{"error":"Try later"}"""))
        server.enqueue(json(200, """{"data":{"etag":"\"only\""}}"""))
        server.enqueue(json(200, uploadResponse.substringAfter("\"data\":").removeSuffix("}")))

        client(partSize = partSize, threshold = partSize.toLong()).upload(BytesUploadSource("blob.bin", data))

        val paths = (1..4).map { server.takeRequest().url.encodedPath }
        assertThat(paths).containsExactly(
            "/api/files/chunks",
            "/api/files/chunks/abc/part/1",
            "/api/files/chunks/abc/part/1",
            "/api/files/chunks/abc/complete",
        ).inOrder()
    }

    private val partSize = 5 * 1024 * 1024
    private val uploadId = "0123456789abcdef0123456789abcdef"
    private val completed = uploadResponse.substringAfter("\"data\":").removeSuffix("}")

    private fun session(id: String = uploadId) = json(200, """{"data":{"uploadId":"$id","fileKey":"uploads/x"}}""")

    private fun part(etag: String) = json(200, """{"data":{"etag":"\"$etag\""}}""")

    /** Three parts: two full ones and 1234 bytes. */
    private fun threeParts() = ByteArray(partSize * 2 + 1234) { (it % 251).toByte() }

    /** A source that, like some content providers, doesn't report its size. */
    private class UnsizedSource(override val fileName: String, private val bytes: ByteArray) : UploadSource {
        override val declaredMimeType: String? = null
        override val size: Long? = null
        override fun open(): InputStream = ByteArrayInputStream(bytes)
    }

    private fun MockWebServer.requests() = (1..requestCount).map { takeRequest() }

    @Test
    fun `files under the threshold go in one request and larger ones in parts`() = runTest {
        val threshold = FlareClient.DEFAULT_CHUNK_THRESHOLD.toInt()
        server.enqueue(json(200, uploadResponse))
        client().upload(BytesUploadSource("under.bin", ByteArray(threshold - 1)))
        val single = server.takeRequest()
        assertThat(single.url.encodedPath).isEqualTo("/api/files")
        // Next.js cuts bodies over 10 MiB, so the whole request must stay under that.
        assertThat(single.bodySize).isLessThan(10L * 1024 * 1024)

        server.enqueue(session())
        server.enqueue(part("e1"))
        server.enqueue(json(200, completed))
        client().upload(BytesUploadSource("at.bin", ByteArray(threshold)))
        val paths = (1..3).map { server.takeRequest() }
        assertThat(paths.map { it.url.encodedPath }).containsExactly(
            "/api/files/chunks",
            "/api/files/chunks/$uploadId/part/1",
            "/api/files/chunks/$uploadId/complete",
        ).inOrder()
        assertThat(paths[1].bodySize).isEqualTo(threshold.toLong())
    }

    @Test
    fun `rejects part sizes and thresholds outside what Flare accepts`() {
        val mib = 1024 * 1024
        assertThat(runCatching { client(partSize = 9 * mib) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { client(partSize = 4 * mib) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { client(threshold = 9L * mib) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { client(partSize = 8 * mib, threshold = 6L * mib) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        client(partSize = 5 * mib, threshold = 8L * mib)
    }

    @Test
    fun `sends a small file of unknown size in one request with its length`() = runTest {
        server.enqueue(json(200, uploadResponse))
        val data = ByteArray(1000) { 3 }
        client().upload(UnsizedSource("notes.bin", data))
        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/api/files")
        assertThat(request.headers["Content-Length"]).isNotNull()
        assertThat(request.headers["Transfer-Encoding"]).isNull()
        assertThat(request.body!!.indexOf(okio.ByteString.of(*data))).isAtLeast(0)

        // A provider's -1 means it doesn't know either.
        server.enqueue(json(200, uploadResponse))
        val minusOne = object : UploadSource by UnsizedSource("notes.bin", data) {
            override val size: Long = -1
        }
        client().upload(minusOne)
        assertThat(server.takeRequest().headers["Content-Length"]).isNotNull()
    }

    @Test
    fun `copies a long file of unknown size to a temporary file and sends it in parts`() = runTest {
        val folder = temp.newFolder()
        val data = threeParts()
        server.enqueue(session())
        server.enqueue(part("e1"))
        server.enqueue(part("e2"))
        server.enqueue(part("e3"))
        server.enqueue(json(200, completed))

        client(partSize = partSize, threshold = partSize.toLong(), tempDirectory = folder).upload(UnsizedSource("video.mp4", data))

        val requests = server.requests()
        assertThat(requests.first().body!!.utf8()).contains("\"size\":${data.size}")
        assertThat(requests.map { it.bodySize }.drop(1).take(3)).containsExactly(partSize.toLong(), partSize.toLong(), 1234L).inOrder()
        assertThat(requests[3].body!!.toByteArray()).isEqualTo(data.copyOfRange(partSize * 2, data.size))
        assertThat(folder.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun `deletes the temporary copy when the upload fails`() = runTest {
        val folder = temp.newFolder()
        server.enqueue(json(403, """{"error":"Your role cannot upload files."}"""))
        val error = runCatching {
            client(partSize = partSize, threshold = partSize.toLong(), tempDirectory = folder).upload(UnsizedSource("video.mp4", threeParts()))
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(FlareException.Forbidden::class.java)
        assertThat(folder.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun `writes names that aren't plain ASCII as an RFC 5987 filename`() = runTest {
        server.enqueue(json(200, uploadResponse))
        server.enqueue(json(200, uploadResponse))
        client().upload(BytesUploadSource("café ✓ 日本.txt", "hi".toByteArray(), "text/plain"))
        client().upload(BytesUploadSource("say \"hi\".txt", "hi".toByteArray(), "text/plain"))

        assertThat(server.takeRequest().body!!.utf8()).contains(
            "Content-Disposition: form-data; name=\"file\"; filename=\"cafe _ __.txt\"; " +
                "filename*=UTF-8''caf%C3%A9%20%E2%9C%93%20%E6%97%A5%E6%9C%AC.txt\r\nContent-Type: text/plain",
        )
        assertThat(server.takeRequest().body!!.utf8()).contains(
            "filename=\"say _hi_.txt\"; filename*=UTF-8''say%20%22hi%22.txt\r\n",
        )
    }

    @Test
    fun `reports the session and every part so the upload can continue later`() = runTest {
        server.enqueue(session())
        server.enqueue(part("e1"))
        server.enqueue(part("e2"))
        server.enqueue(part("e3"))
        server.enqueue(json(200, completed))
        val states = mutableListOf<ChunkedProgress>()
        val data = threeParts()

        client(partSize = partSize, threshold = partSize.toLong()).upload(BytesUploadSource("big.bin", data), onChunkedProgress = { states += it })

        assertThat(states.map { it.parts.size }).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(states.map { it.uploadId }.distinct()).containsExactly(uploadId)
        assertThat(states.last()).isEqualTo(
            ChunkedProgress(uploadId, partSize, data.size.toLong(), listOf(CompletedPart(1, "\"e1\""), CompletedPart(2, "\"e2\""), CompletedPart(3, "\"e3\""))),
        )
        assertThat(states[2].uploadedBytes).isEqualTo(partSize * 2L)
        assertThat(states.last().uploadedBytes).isEqualTo(data.size.toLong())
    }

    @Test
    fun `resumes after the parts Flare already has`() = runTest {
        val data = threeParts()
        val saved = ChunkedProgress(uploadId, partSize, data.size.toLong(), listOf(CompletedPart(1, "\"e1\"")))
        server.enqueue(part("e2"))
        server.enqueue(part("e3"))
        server.enqueue(json(200, completed))
        val progress = mutableListOf<Long>()
        val states = mutableListOf<ChunkedProgress>()

        client(partSize = partSize, threshold = partSize.toLong()).upload(
            BytesUploadSource("big.bin", data),
            progress = { sent, _ -> progress += sent },
            resume = saved,
            onChunkedProgress = { states += it },
        )

        val requests = server.requests()
        assertThat(requests.map { "${it.method} ${it.url.encodedPath}" }).containsExactly(
            "PUT /api/files/chunks/$uploadId/part/2",
            "PUT /api/files/chunks/$uploadId/part/3",
            "POST /api/files/chunks/$uploadId/complete",
        ).inOrder()
        assertThat(requests[0].body!!.toByteArray()).isEqualTo(data.copyOfRange(partSize, partSize * 2))
        assertThat(requests[2].body!!.utf8()).isEqualTo(
            """{"parts":[{"PartNumber":1,"ETag":"\"e1\""},{"PartNumber":2,"ETag":"\"e2\""},{"PartNumber":3,"ETag":"\"e3\""}]}""",
        )
        assertThat(progress.first()).isEqualTo(partSize.toLong())
        assertThat(progress.last()).isEqualTo(data.size.toLong())
        assertThat(progress).isInOrder()
        assertThat(states.map { it.parts.size }).containsExactly(2, 3).inOrder()
    }

    @Test
    fun `completes right away when every part was already sent`() = runTest {
        val data = threeParts()
        val parts = (1..3).map { CompletedPart(it, "\"e$it\"") }
        server.enqueue(json(200, completed))
        client(partSize = partSize, threshold = partSize.toLong())
            .upload(BytesUploadSource("big.bin", data), resume = ChunkedProgress(uploadId, partSize, data.size.toLong(), parts))
        assertThat(server.requests().map { it.url.encodedPath }).containsExactly("/api/files/chunks/$uploadId/complete")
    }

    @Test
    fun `starts a new session when Flare forgot the saved one`() = runTest {
        val data = threeParts()
        val saved = ChunkedProgress(uploadId, partSize, data.size.toLong(), listOf(CompletedPart(1, "\"old\"")))
        val fresh = "fedcba9876543210fedcba9876543210"
        server.enqueue(json(404, """{"error":"Upload not found."}"""))
        server.enqueue(session(fresh))
        server.enqueue(part("n1"))
        server.enqueue(part("n2"))
        server.enqueue(part("n3"))
        server.enqueue(json(200, completed))
        val progress = mutableListOf<Long>()
        val states = mutableListOf<ChunkedProgress>()

        val result = client(partSize = partSize, threshold = partSize.toLong()).upload(
            BytesUploadSource("big.bin", data),
            progress = { sent, _ -> progress += sent },
            resume = saved,
            onChunkedProgress = { states += it },
        )

        assertThat(result.name).isEqualTo("a.png")
        val requests = server.requests()
        assertThat(requests.map { "${it.method} ${it.url.encodedPath}" }).containsExactly(
            "PUT /api/files/chunks/$uploadId/part/2",
            "POST /api/files/chunks",
            "PUT /api/files/chunks/$fresh/part/1",
            "PUT /api/files/chunks/$fresh/part/2",
            "PUT /api/files/chunks/$fresh/part/3",
            "POST /api/files/chunks/$fresh/complete",
        ).inOrder()
        assertThat(requests[2].body!!.toByteArray()).isEqualTo(data.copyOfRange(0, partSize))
        assertThat(states.map { it.uploadId to it.parts.size }).containsExactly(
            fresh to 0, fresh to 1, fresh to 2, fresh to 3,
        ).inOrder()
        // Progress starts from the saved bytes, then from zero for the new session.
        assertThat(progress.first()).isEqualTo(partSize.toLong())
        assertThat(progress).contains(0L)
        assertThat(progress.last()).isEqualTo(data.size.toLong())
    }

    @Test
    fun `starts over when completion finds no session`() = runTest {
        val data = threeParts()
        val parts = (1..3).map { CompletedPart(it, "\"e$it\"") }
        server.enqueue(json(404, """{"error":"Upload not found."}"""))
        server.enqueue(session("abc"))
        repeat(3) { server.enqueue(part("n$it")) }
        server.enqueue(json(200, completed))
        client(partSize = partSize, threshold = partSize.toLong())
            .upload(BytesUploadSource("big.bin", data), resume = ChunkedProgress(uploadId, partSize, data.size.toLong(), parts))
        assertThat(server.requests().map { it.url.encodedPath }).containsExactly(
            "/api/files/chunks/$uploadId/complete",
            "/api/files/chunks",
            "/api/files/chunks/abc/part/1",
            "/api/files/chunks/abc/part/2",
            "/api/files/chunks/abc/part/3",
            "/api/files/chunks/abc/complete",
        ).inOrder()
    }

    @Test
    fun `starts a new session after Flare's storage changed`() = runTest {
        val changed = """{"error":"Storage changed during this upload. Please start a new upload."}"""
        server.enqueue(session("first"))
        server.enqueue(part("p1"))
        server.enqueue(json(409, changed))
        // Completing shows whether the 409 meant a finished upload; this one is a storage change.
        server.enqueue(json(409, changed))
        server.enqueue(session("second"))
        repeat(3) { server.enqueue(part("p$it")) }
        server.enqueue(json(200, completed))
        client(partSize = partSize, threshold = partSize.toLong()).upload(BytesUploadSource("big.bin", threeParts()))
        val paths = server.requests().map { it.url.encodedPath }
        assertThat(paths.take(6)).containsExactly(
            "/api/files/chunks",
            "/api/files/chunks/first/part/1",
            "/api/files/chunks/first/part/2",
            "/api/files/chunks/first/complete",
            "/api/files/chunks",
            "/api/files/chunks/second/part/1",
        ).inOrder()
    }

    @Test
    fun `returns the stored file when a lagging state resumes a finished upload`() = runTest {
        val data = threeParts()
        val lagging = ChunkedProgress(uploadId, partSize, data.size.toLong(), listOf(CompletedPart(1, "\"e1\"")))
        server.enqueue(json(409, """{"error":"This upload is already complete."}"""))
        server.enqueue(json(200, completed))
        val result = client(partSize = partSize, threshold = partSize.toLong())
            .upload(BytesUploadSource("big.bin", data), resume = lagging)
        assertThat(result.name).isEqualTo("a.png")
        assertThat(server.requests().map { "${it.method} ${it.url.encodedPath}" }).containsExactly(
            "PUT /api/files/chunks/$uploadId/part/2",
            "POST /api/files/chunks/$uploadId/complete",
        ).inOrder()
    }

    @Test
    fun `ignores a saved state that belongs to another file`() = runTest {
        val data = threeParts()
        server.enqueue(session())
        repeat(3) { server.enqueue(part("e$it")) }
        server.enqueue(json(200, completed))
        val other = ChunkedProgress("other", partSize, data.size + 1L, listOf(CompletedPart(1, "\"x\"")))
        client(partSize = partSize, threshold = partSize.toLong()).upload(BytesUploadSource("big.bin", data), resume = other)
        assertThat(server.requests().map { it.url.encodedPath }.first()).isEqualTo("/api/files/chunks")
    }

    @Test
    fun `gives up when the new session is lost as well`() = runTest {
        val data = threeParts()
        val saved = ChunkedProgress(uploadId, partSize, data.size.toLong(), listOf(CompletedPart(1, "\"e1\"")))
        server.enqueue(json(404, """{"error":"Upload not found."}"""))
        server.enqueue(session("again"))
        server.enqueue(json(404, """{"error":"Upload not found."}"""))
        val error = runCatching {
            client(partSize = partSize, threshold = partSize.toLong()).upload(BytesUploadSource("big.bin", data), resume = saved)
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(FlareException.NotFound::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `uses larger parts when a file would need more than 10,000`() = runTest {
        val size = 60L * 1024 * 1024 * 1024
        val huge = object : UploadSource {
            override val fileName = "huge.mkv"
            override val declaredMimeType = "video/x-matroska"
            override val size: Long = size
            override fun open(): InputStream = object : InputStream() {
                override fun read(): Int = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { b.fill(0, off, off + len) }
            }
        }
        server.enqueue(session())
        server.enqueue(json(400, """{"error":"Stop here."}"""))
        val states = mutableListOf<ChunkedProgress>()
        val error = runCatching {
            client(partSize = partSize, threshold = partSize.toLong()).upload(huge, onChunkedProgress = { states += it })
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(FlareException.BadRequest::class.java)
        val expected = (size + 9_999) / 10_000
        assertThat(states.single().partSize.toLong()).isEqualTo(expected)
        server.takeRequest()
        assertThat(server.takeRequest().bodySize).isEqualTo(expected)

        val tooLarge = object : UploadSource by huge {
            override val size: Long = 80L * 1024 * 1024 * 1024
        }
        val refused = runCatching { client().upload(tooLarge) }.exceptionOrNull()
        assertThat(refused).isInstanceOf(FlareException.TooLarge::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `refuses a paste that can't fit in one request`() = runTest {
        val text = ByteArray(10 * 1024 * 1024) { 'a'.code.toByte() }
        val sized = runCatching { client().upload(BytesUploadSource("big.txt", text), kind = UploadKind.PASTE) }.exceptionOrNull()
        assertThat(sized).isInstanceOf(FlareException.TooLarge::class.java)
        val unsized = runCatching { client().upload(UnsizedSource("big.txt", text), kind = UploadKind.PASTE) }.exceptionOrNull()
        assertThat(unsized).isInstanceOf(FlareException.TooLarge::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a file shorter than its reported size is a temporary failure`() = runTest {
        val short = object : UploadSource {
            override val fileName = "growing.mp4"
            override val declaredMimeType = "video/mp4"
            override val size: Long = partSize * 2L
            override fun open(): InputStream = ByteArrayInputStream(ByteArray(partSize + 10))
        }
        server.enqueue(session())
        server.enqueue(part("e1"))
        val error = runCatching { client(partSize = partSize, threshold = partSize.toLong()).upload(short) }.exceptionOrNull()
        // A problem with the file, not the connection, so the app doesn't blame the network.
        assertThat(error).isInstanceOf(FlareException.SourceRead::class.java)
        assertThat((error as FlareException).isRetryable).isTrue()
    }

    @Test
    fun `a completion that failed on Flare's side starts the upload over once`() = runTest {
        val data = ByteArray(partSize + 10) { it.toByte() }
        server.enqueue(session("first"))
        server.enqueue(part("e1"))
        server.enqueue(part("e2"))
        // Flare discarded the parts and answers every later completion of this session the same way.
        repeat(4) { server.enqueue(json(500, """{"error":"Upload could not be completed."}""")) }
        server.enqueue(session("second"))
        server.enqueue(part("e1"))
        server.enqueue(part("e2"))
        server.enqueue(json(200, completed))

        client(partSize = partSize, threshold = partSize.toLong()).upload(BytesUploadSource("film.bin", data))
        val paths = (1..server.requestCount).map { server.takeRequest().url.encodedPath }
        assertThat(paths.last()).isEqualTo("/api/files/chunks/second/complete")
    }

    @Test
    fun `chunk progress survives a round trip through JSON`() {
        val state = ChunkedProgress(uploadId, partSize, 12_345_678, listOf(CompletedPart(1, "\"e1\"")))
        assertThat(ChunkedProgress.fromJson(state.toJson())).isEqualTo(state)
        assertThat(ChunkedProgress.fromJson("""{"uploadId":"a","partSize":5,"totalSize":9,"parts":[],"future":true}""")?.uploadId).isEqualTo("a")
        assertThat(ChunkedProgress.fromJson("not json")).isNull()
        assertThat(ChunkedProgress.fromJson("""{"uploadId":"a"}""")).isNull()
    }

    @Test
    fun `reads responses and files away from the calling thread`() {
        // Android forbids network reads on the main thread, so calls must be main-safe.
        val callerThreads = Collections.synchronizedList(mutableListOf<Thread>())
        val bodyReaders = Collections.synchronizedList(mutableListOf<Thread>())
        val fileReaders = Collections.synchronizedList(mutableListOf<Thread>())
        val recording = http.newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            response.newBuilder().body(
                object : ResponseBody() {
                    override fun contentType() = body.contentType()

                    override fun contentLength() = body.contentLength()

                    override fun source(): BufferedSource = body.source().also { bodyReaders += Thread.currentThread() }
                },
            ).build()
        }.build()
        val client = FlareClient(FlareOrigin.parse(server.url("/").toString()), FlareCredential.of("flr_test-token"), recording)
        val file = object : UploadSource {
            override val fileName = "a.png"
            override val declaredMimeType = "image/png"
            override val size: Long = png.size.toLong()
            override fun open(): InputStream = object : ByteArrayInputStream(png) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { fileReaders += Thread.currentThread() }
            }
        }
        val link = """{"id":"cm_link","shortCode":"aB3_dE","targetUrl":"https://example.com/a","clicks":0,"createdAt":"2026-09-20T12:00:00.000Z"}"""
        server.enqueue(json(200, """{"success":true,"data":$link}"""))
        server.enqueue(json(200, """{"success":true,"data":{"urls":[$link]}}"""))
        server.enqueue(json(200, uploadResponse))

        Executors.newSingleThreadExecutor { Thread(it).also(callerThreads::add) }.asCoroutineDispatcher().use { caller ->
            runBlocking(caller) {
                client.createShortLink("https://example.com/a")
                client.listShortLinks()
                client.upload(file)
            }
        }

        val caller = callerThreads.single()
        assertThat(bodyReaders).isNotEmpty()
        assertThat(bodyReaders).doesNotContain(caller)
        assertThat(fileReaders).isNotEmpty()
        assertThat(fileReaders).doesNotContain(caller)
    }

    @Test
    fun `maps Flare errors to typed exceptions with server messages`() = runTest {
        suspend fun failure(response: MockResponse): FlareException {
            server.enqueue(response)
            val error = runCatching { client().upload(BytesUploadSource("a.png", png)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(FlareException::class.java)
            return error as FlareException
        }
        assertThat(failure(json(401, """{"error":"Unauthorized"}"""))).isInstanceOf(FlareException.Unauthorized::class.java)
        failure(json(403, """{"success":false,"error":"You do not have permission to create pastes."}""")).let {
            assertThat(it).isInstanceOf(FlareException.Forbidden::class.java)
            assertThat(it.message).isEqualTo("You do not have permission to create pastes.")
        }
        failure(MockResponse.Builder().code(413).body("<html>413 Request Entity Too Large</html>").build()).let {
            assertThat(it).isInstanceOf(FlareException.TooLarge::class.java)
            assertThat(it.message).isEqualTo("The file is larger than this Flare instance accepts.")
        }
        failure(json(429, """{"error":"Too many requests"}""", "Retry-After" to "60")).let {
            assertThat(it).isInstanceOf(FlareException.RateLimited::class.java)
            assertThat((it as FlareException.RateLimited).retryAfterSeconds).isEqualTo(60)
            assertThat(it.isRetryable).isTrue()
        }
        // Flare cleans up an upload it couldn't commit, so outages and gateway errors retry.
        failure(json(500, """{"success":false,"error":"Upload failed"}""")).let {
            assertThat(it).isInstanceOf(FlareException.Server::class.java)
            assertThat(it.isRetryable).isTrue()
        }
        failure(MockResponse.Builder().code(524).body("<html>A timeout occurred</html>").build()).let {
            assertThat(it).isInstanceOf(FlareException.Server::class.java)
            assertThat(it.isRetryable).isTrue()
        }
        failure(json(408, """{"error":"Request timeout"}""")).let { assertThat(it.isRetryable).isTrue() }
        failure(json(501, """{"error":"Not implemented"}""")).let { assertThat(it.isRetryable).isFalse() }
    }

    @Test
    fun `a file that disappeared before upload is reported as unavailable`() = runTest {
        val gone = object : UploadSource {
            override val fileName = "deleted.jpg"
            override val declaredMimeType = "image/jpeg"
            override val size: Long = 10
            override fun open(): java.io.InputStream = throw java.io.FileNotFoundException("gone")
        }
        val error = runCatching { client().upload(gone) }.exceptionOrNull()
        assertThat(error).isInstanceOf(FlareException.SourceUnavailable::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a Wi-Fi sign-in page is a temporary network problem`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "text/html").body("<html>Sign in to Hotel Wi-Fi</html>").build())
        val error = runCatching { client().upload(BytesUploadSource("note.txt", "hi".toByteArray(), "text/plain")) }.exceptionOrNull()
        assertThat(error).isInstanceOf(FlareException.Network::class.java)
        assertThat((error as FlareException).isRetryable).isTrue()
    }

    @Test
    fun `unreadable JSON from Flare is not retried, because the file may be stored`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"unexpected":true}}"""))
        val error = runCatching { client().upload(BytesUploadSource("note.txt", "hi".toByteArray(), "text/plain")) }.exceptionOrNull()
        assertThat(error).isInstanceOf(FlareException.UnexpectedResponse::class.java)
        assertThat((error as FlareException).isRetryable).isFalse()
    }

    @Test
    fun `an outage during the access check is an error, not a missing scope`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"status":"ok"}}"""))
        repeat(5) { server.enqueue(json(503, """{"error":"Service unavailable"}""")) }
        val error = runCatching { client().probeCapabilities() }.exceptionOrNull()
        assertThat(error).isInstanceOf(FlareException.Server::class.java)
    }

    @Test
    fun `a dropped connection during the access check is an error, not a missing scope`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"status":"ok"}}"""))
        server.close()
        val error = runCatching { client().probeCapabilities() }.exceptionOrNull()
        assertThat(error).isInstanceOf(FlareException.Network::class.java)
    }

    @Test
    fun `lists files with filters and converts MiB sizes`() = runTest {
        server.enqueue(
            json(
                200,
                """{"success":true,"data":[{"id":"cm1","name":"invoice.png","urlPath":"/abc/invoice.png","mimeType":"image/png",
                "size":0.5,"uploadedAt":"2026-09-20T12:00:00.000Z","visibility":"PRIVATE","views":3,"downloads":1,"folderId":null,
                "user":{"urlId":"abc"},"tags":[{"id":"t1","name":"Finance"}],"hasPassword":false,"expiresAt":null}],
                "pagination":{"total":1,"pageCount":1,"page":2,"limit":10}}""",
            ),
        )
        val page = client().listFiles(FileQuery(page = 2, limit = 10, search = " invoice ", types = listOf("image/png", "image/jpeg")))
        val file = page.files.single()
        assertThat(file.sizeBytes).isEqualTo(524_288)
        assertThat(file.isPrivate).isTrue()
        assertThat(file.tags.single().name).isEqualTo("Finance")
        assertThat(page.pagination.page).isEqualTo(2)
        assertThat(client().pageUrl(file)).endsWith("/abc/invoice.png")

        val url = server.takeRequest().url
        assertThat(url.queryParameter("page")).isEqualTo("2")
        assertThat(url.queryParameter("limit")).isEqualTo("10")
        assertThat(url.queryParameter("search")).isEqualTo("invoice")
        assertThat(url.queryParameter("types")).isEqualTo("image/png,image/jpeg")
        assertThat(url.queryParameter("sortBy")).isEqualTo("newest")
    }

    @Test
    fun `creates, lists and deletes short links`() = runTest {
        val link = """{"id":"cm_link","shortCode":"aB3_dE","targetUrl":"https://example.com/a","clicks":0,"createdAt":"2026-09-20T12:00:00.000Z","userId":"u"}"""
        server.enqueue(json(200, """{"success":true,"data":$link}"""))
        server.enqueue(json(200, """{"success":true,"data":{"urls":[$link]}}"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(json(404, """{"success":false,"error":"URL not found"}"""))

        val created = client().createShortLink("https://example.com/a")
        assertThat(client().shortLinkUrl(created, FlareOrigin.parse("https://files.example.com")))
            .isEqualTo("https://files.example.com/u/aB3_dE")
        assertThat(client().listShortLinks().single().id).isEqualTo("cm_link")
        client().deleteShortLink("cm_link")
        client().deleteShortLink("cm_link")

        assertThat(server.takeRequest().body!!.utf8()).isEqualTo("""{"url":"https://example.com/a"}""")
        assertThat(server.takeRequest().method).isEqualTo("GET")
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
    }

    @Test
    fun `lists folders for account tokens`() = runTest {
        server.enqueue(
            json(200, """{"success":true,"data":[{"id":"f1","name":"Screenshots","parentId":null,"fileCount":4,"shareToken":null}]}"""),
        )
        val folders = client(token = "legacy-account-token").listFolders()
        assertThat(folders.single().name).isEqualTo("Screenshots")
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer legacy-account-token")
    }
}
