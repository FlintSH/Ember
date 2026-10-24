package dev.fl1nt.ember.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Runs Ember's client against a real Flare instance.
 *
 * Set `FLARE_FIXTURE` to the JSON written by `scripts/flare-fixture/bootstrap.py`.
 * The suite skips itself when the variable is missing, so `./gradlew check` stays
 * hermetic. Every file it creates has a unique `ember-it-` name.
 */
class LiveFlareTest {

    @Serializable
    data class Tokens(val all: String, val uploadOnly: String, val account: String)

    @Serializable
    data class Fixture(val url: String, val tokens: Tokens, val folderId: String)

    companion object {
        private lateinit var fixture: Fixture
        private val json = Json { ignoreUnknownKeys = true }
        private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()

        // One limiter for the whole run keeps the suite under Flare's 30 uploads per minute.
        private val limiter = UploadStartLimiter()

        @BeforeClass
        @JvmStatic
        fun loadFixture() {
            val path = System.getenv("FLARE_FIXTURE")
            assumeTrue("FLARE_FIXTURE is not set; skipping live Flare tests", !path.isNullOrBlank())
            fixture = json.decodeFromString(Fixture.serializer(), File(path!!).readText())
        }
    }

    @get:Rule
    val temp = TemporaryFolder()

    private fun client(
        token: String,
        partSize: Int = FlareClient.DEFAULT_PART_SIZE,
        threshold: Long = FlareClient.DEFAULT_CHUNK_THRESHOLD,
        tempDirectory: File? = null,
    ) = FlareClient(FlareOrigin.parse(fixture.url), FlareCredential.of(token), http, limiter, partSize, threshold, tempDirectory)

    private val png: ByteArray = javaClass.getResourceAsStream("/pixel.png")?.readBytes()
        ?: error("pixel.png test resource is missing")

    private fun uniqueName(extension: String) = "ember-it-${UUID.randomUUID().toString().take(8)}.$extension"

    private suspend fun findByName(client: FlareClient, name: String): RemoteFile? =
        client.listFiles(FileQuery(search = name, limit = 10)).files.firstOrNull { it.name == name }

    @Test
    fun `reports health and each token's capabilities`() = runBlocking {
        client(fixture.tokens.all).checkHealth()

        val all = client(fixture.tokens.all).probeCapabilities()
        assertThat(all.uploadFiles).isEqualTo(Access.GRANTED)
        assertThat(all.readFiles).isEqualTo(Access.GRANTED)
        assertThat(all.readLinks).isEqualTo(Access.GRANTED)
        assertThat(all.manageLinks).isEqualTo(Access.GRANTED)
        assertThat(all.listFolders).isEqualTo(Access.NOT_IN_TOKEN)

        val uploadOnly = client(fixture.tokens.uploadOnly).probeCapabilities()
        assertThat(uploadOnly.uploadFiles).isEqualTo(Access.GRANTED)
        assertThat(uploadOnly.readFiles).isEqualTo(Access.NOT_IN_TOKEN)
        assertThat(uploadOnly.readLinks).isEqualTo(Access.NOT_IN_TOKEN)
        assertThat(uploadOnly.manageLinks).isEqualTo(Access.NOT_IN_TOKEN)

        val account = client(fixture.tokens.account).probeCapabilities()
        assertThat(account.uploadFiles).isEqualTo(Access.GRANTED)
        assertThat(account.listFolders).isEqualTo(Access.GRANTED)

        val bogus = runCatching { client("flr_not-a-real-token").probeCapabilities() }.exceptionOrNull()
        assertThat(bogus).isInstanceOf(FlareException.Unauthorized::class.java)
    }

    @Test
    fun `uploads a private, expiring, password protected file and finds it in the library`() = runBlocking {
        val name = uniqueName("png")
        val client = client(fixture.tokens.all)
        val uploaded = client.upload(
            BytesUploadSource(name, png, declaredMimeType = "application/octet-stream"),
            UploadOptions(visibility = Visibility.PRIVATE, password = "correct horse", expiration = Expiration.DAY, expiryAction = ExpiryAction.DELETE),
        )
        assertThat(uploaded.name).isEqualTo(name)
        assertThat(uploaded.type).isEqualTo("image/png")
        assertThat(uploaded.size).isEqualTo(png.size.toLong())
        assertThat(uploaded.shareUrl).startsWith(fixture.url)

        val listed = findByName(client, name)
        assertThat(listed).isNotNull()
        assertThat(listed!!.isPrivate).isTrue()
        assertThat(listed.hasPassword).isTrue()
        assertThat(listed.expiresAt).isNotNull()
        assertThat(listed.sizeBytes).isEqualTo(png.size.toLong())
        assertThat(client.pageUrl(listed)).isEqualTo(uploaded.shareUrl)
    }

    @Test
    fun `public uploads serve thumbnails without a token`() = runBlocking {
        val name = uniqueName("png")
        val client = client(fixture.tokens.all)
        client.upload(BytesUploadSource(name, png), UploadOptions(visibility = Visibility.PUBLIC))
        val listed = findByName(client, name)!!
        val response = http.newCall(Request.Builder().url(client.thumbnailUrl(listed)).build()).execute()
        response.use { assertThat(it.code).isEqualTo(200) }
    }

    @Test
    fun `uploads into a folder chosen before the bytes are sent`() = runBlocking {
        val name = uniqueName("png")
        val account = client(fixture.tokens.account)
        account.upload(BytesUploadSource(name, png), UploadOptions(folder = Selection.Id(fixture.folderId)))
        val inFolder = account.listFiles(FileQuery(search = name, folderId = fixture.folderId)).files
        assertThat(inFolder.map { it.name }).contains(name)
        assertThat(account.listFolders().map { it.id }).contains(fixture.folderId)
    }

    @Test
    fun `creates pastes as text files`() = runBlocking {
        val name = uniqueName("md")
        val client = client(fixture.tokens.all)
        val paste = client.upload(BytesUploadSource(name, "# Hello from Ember\n".toByteArray()), kind = UploadKind.PASTE)
        assertThat(paste.type).isEqualTo("text/plain")
        assertThat(findByName(client, name)?.mimeType).isEqualTo("text/plain")
    }

    @Test
    fun `uploads large files in parts`() = runBlocking {
        val name = uniqueName("png")
        val partSize = 5 * 1024 * 1024
        val data = ByteArray(partSize * 2 + 4321) { (it * 31 % 251).toByte() }
        System.arraycopy(png, 0, data, 0, png.size)
        val client = client(fixture.tokens.all, partSize = partSize, threshold = partSize.toLong())
        var lastProgress = 0L
        val uploaded = client.upload(BytesUploadSource(name, data), progress = { sent, _ -> lastProgress = sent })
        assertThat(uploaded.size).isEqualTo(data.size.toLong())
        assertThat(lastProgress).isEqualTo(data.size.toLong())
        assertThat(findByName(client, name)?.sizeBytes).isEqualTo(data.size.toLong())
    }

    /** A source that, like some content providers, doesn't report its size. */
    private class UnsizedSource(override val fileName: String, private val bytes: ByteArray) : UploadSource {
        override val declaredMimeType: String? = null
        override val size: Long? = null
        override fun open(): InputStream = ByteArrayInputStream(bytes)
    }

    private class Abandoned : RuntimeException()

    private fun download(url: String): ByteArray =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertThat(response.code).isEqualTo(200)
            response.body.bytes()
        }

    @Test
    fun `uploads files that are too large for one request`() = runBlocking {
        // Next.js cuts a request body at 10 MiB, which once made this a 500.
        val client = client(fixture.tokens.all)
        val known = ByteArray(12 * 1024 * 1024) { (it % 253).toByte() }
        val name = uniqueName("bin")
        val uploaded = client.upload(BytesUploadSource(name, known), UploadOptions(visibility = Visibility.PUBLIC))
        assertThat(uploaded.size).isEqualTo(known.size.toLong())
        assertThat(findByName(client, name)?.sizeBytes).isEqualTo(known.size.toLong())
        assertThat(download(uploaded.rawUrl!!)).isEqualTo(known)

        val folder = temp.newFolder()
        val spooling = client(fixture.tokens.all, tempDirectory = folder)
        val unknown = ByteArray(11 * 1024 * 1024) { (it % 241).toByte() }
        val unsizedName = uniqueName("bin")
        val fromStream = spooling.upload(UnsizedSource(unsizedName, unknown), UploadOptions(visibility = Visibility.PUBLIC))
        assertThat(fromStream.size).isEqualTo(unknown.size.toLong())
        assertThat(download(fromStream.rawUrl!!)).isEqualTo(unknown)
        assertThat(folder.listFiles()!!.toList()).isEmpty()

        val small = spooling.upload(UnsizedSource(uniqueName("txt"), "small and unsized\n".toByteArray()))
        assertThat(small.size).isEqualTo(18)
    }

    @Test
    fun `keeps names that aren't plain ASCII`() = runBlocking {
        val client = client(fixture.tokens.all)
        val single = "ember-it-${UUID.randomUUID().toString().take(8)} café ✓ 日本.txt"
        assertThat(client.upload(BytesUploadSource(single, "hi\n".toByteArray(), "text/plain")).name).isEqualTo(single)
        assertThat(findByName(client, single)?.name).isEqualTo(single)
        val quoted = "ember-it-${UUID.randomUUID().toString().take(8)} \"quoted\" ñ.txt"
        assertThat(client.upload(BytesUploadSource(quoted, "hi\n".toByteArray(), "text/plain")).name).isEqualTo(quoted)

        val partSize = 5 * 1024 * 1024
        val chunked = "ember-it-${UUID.randomUUID().toString().take(8)} Ålesund 🌊.bin"
        val parts = client(fixture.tokens.all, partSize = partSize, threshold = partSize.toLong())
        assertThat(parts.upload(BytesUploadSource(chunked, ByteArray(partSize + 100))).name).isEqualTo(chunked)
        assertThat(findByName(client, chunked)?.name).isEqualTo(chunked)
    }

    @Test
    fun `uploads phone formats that Flare's type check is strict about`() = runBlocking {
        val client = client(fixture.tokens.all)
        // Each sample: what a content provider reports, and the type Flare should store.
        val samples = listOf(
            Triple("PXL_0001.dng", "image/x-adobe-dng", Samples.dng()) to "image/x-adobe-dng",
            Triple("IMG_0001.CR2", null, Samples.cr2()) to "image/x-canon-cr2",
            Triple("burst.heic", "image/heic", Samples.isoMedia("hevc")) to "image/heic-sequence",
            Triple("burst.heif", "image/heif", Samples.isoMedia("msf1")) to "image/heif-sequence",
            Triple("photo.heic", "image/heic", Samples.isoMedia("mif1")) to "image/heic",
            Triple("sticker.png", "image/png", Samples.apng()) to "image/apng",
            Triple("voice.3gp", "audio/3gpp", Samples.isoMedia("3gp4")) to "video/3gpp",
            Triple("voice.m4a", "audio/mp4", Samples.isoMedia("isom")) to "audio/mp4",
            Triple("clip.ogg", "audio/ogg", Samples.oggTheora()) to "video/ogg",
            Triple("voice.webm", "audio/webm", Samples.webm()) to "video/webm",
            Triple("ride.gpx", "application/gpx+xml", "<?xml version=\"1.0\"?><gpx version=\"1.1\"/>".toByteArray()) to "application/xml",
            Triple("Alex.vcf", "text/x-vcard", "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Alex\r\nEND:VCARD\r\n".toByteArray()) to "text/vcard",
            Triple("app.apk", "application/vnd.android.package-archive", Samples.apk(signatureFirst = false)) to
                "application/vnd.android.package-archive",
            Triple("signed.apk", "application/vnd.android.package-archive", Samples.apk(signatureFirst = true)) to "application/java-archive",
            Triple("report.docx", MimeTypes.OCTET_STREAM, Samples.office("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")) to
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            Triple("macro.docm", null, Samples.office("application/vnd.ms-word.document.macroEnabled.main+xml")) to
                "application/vnd.ms-word.document.macroenabled.12",
            Triple("old.doc", "application/msword", Samples.compoundFile()) to "application/x-cfb",
        )
        for ((sample, expected) in samples) {
            val (name, declared, bytes) = sample
            val uploaded = client.upload(BytesUploadSource("${uniqueName("x").removeSuffix(".x")}-$name", bytes, declared))
            assertWithMessage(name).that(uploaded.type).isEqualTo(expected)
        }
    }

    @Test
    fun `continues an abandoned chunked upload where it stopped`() = runBlocking {
        val partSize = 5 * 1024 * 1024
        val data = ByteArray(partSize * 2 + 4321) { (it * 7 % 251).toByte() }
        val name = uniqueName("bin")
        val client = client(fixture.tokens.all, partSize = partSize, threshold = partSize.toLong())
        val options = UploadOptions(visibility = Visibility.PUBLIC)

        var saved: ChunkedProgress? = null
        val stopped = runCatching {
            client.upload(BytesUploadSource(name, data), options, onChunkedProgress = { state ->
                saved = state
                if (state.parts.size == 2) throw Abandoned()
            })
        }.exceptionOrNull()
        assertThat(stopped).isInstanceOf(Abandoned::class.java)
        // An app keeps the state as JSON between attempts.
        val resume = ChunkedProgress.fromJson(saved!!.toJson())!!
        assertThat(resume.parts.map { it.partNumber }).containsExactly(1, 2).inOrder()

        val states = mutableListOf<ChunkedProgress>()
        val progress = mutableListOf<Long>()
        val uploaded = client.upload(
            BytesUploadSource(name, data),
            options,
            progress = { sent, _ -> progress += sent },
            resume = resume,
            onChunkedProgress = { states += it },
        )
        assertThat(states.map { it.uploadId }.distinct()).containsExactly(resume.uploadId)
        assertThat(states.single().parts.size).isEqualTo(3)
        assertThat(progress.first()).isEqualTo(partSize * 2L)
        assertThat(progress.last()).isEqualTo(data.size.toLong())
        assertThat(uploaded.size).isEqualTo(data.size.toLong())
        assertThat(download(uploaded.rawUrl!!)).isEqualTo(data)

        // If the completion's response was lost, completing again returns the same file.
        val again = client.upload(BytesUploadSource(name, data), options, resume = states.single())
        assertThat(again.pageUrl).isEqualTo(uploaded.pageUrl)
    }

    @Test
    fun `starts over when Flare no longer knows the saved session`() = runBlocking {
        val partSize = 5 * 1024 * 1024
        val data = ByteArray(partSize + 999) { (it % 199).toByte() }
        val gone = ChunkedProgress(UUID.randomUUID().toString().replace("-", ""), partSize, data.size.toLong(), listOf(CompletedPart(1, "\"gone\"")))
        val states = mutableListOf<ChunkedProgress>()
        val uploaded = client(fixture.tokens.all, partSize = partSize, threshold = partSize.toLong())
            .upload(BytesUploadSource(uniqueName("bin"), data), UploadOptions(visibility = Visibility.PUBLIC), resume = gone, onChunkedProgress = { states += it })
        assertThat(states.first().uploadId).isNotEqualTo(gone.uploadId)
        assertThat(states.first().parts).isEmpty()
        assertThat(states.last().parts.size).isEqualTo(2)
        assertThat(download(uploaded.rawUrl!!)).isEqualTo(data)
    }

    @Test
    fun `creates, lists and deletes short links`() = runBlocking {
        val client = client(fixture.tokens.all)
        val target = "https://example.com/ember/${UUID.randomUUID()}"
        val link = client.createShortLink(target)
        assertThat(link.targetUrl).isEqualTo(target)
        assertThat(client.listShortLinks().map { it.id }).contains(link.id)
        client.deleteShortLink(link.id)
        assertThat(client.listShortLinks().map { it.id }).doesNotContain(link.id)
        // Deleting again counts as done.
        client.deleteShortLink(link.id)
    }

    @Test
    fun `scopes are enforced`() = runBlocking {
        val uploadOnly = client(fixture.tokens.uploadOnly)
        uploadOnly.upload(BytesUploadSource(uniqueName("png"), png))
        val read = runCatching { uploadOnly.listFiles() }.exceptionOrNull()
        assertThat(read).isInstanceOf(FlareException.Unauthorized::class.java)
        val links = runCatching { uploadOnly.createShortLink("https://example.com") }.exceptionOrNull()
        assertThat(links).isInstanceOf(FlareException.Unauthorized::class.java)
        val folders = runCatching { client(fixture.tokens.all).listFolders() }.exceptionOrNull()
        assertThat(folders).isInstanceOf(FlareException.Unauthorized::class.java)
    }

    @Test
    fun `server validation errors keep Flare's message`() = runBlocking {
        val error = runCatching {
            client(fixture.tokens.all).upload(
                BytesUploadSource(uniqueName("png"), png),
                UploadOptions(folder = Selection.Id("folder-that-does-not-exist")),
            )
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(FlareException::class.java)
        assertThat(error!!.message).isNotEmpty()
    }

    /** Minimal headers that Flare's detector reads the way real files are read. */
    private object Samples {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        private fun buffer(size: Int, bigEndian: Boolean = false): ByteBuffer =
            ByteBuffer.allocate(size).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)

        private fun directory(tags: List<Int>): ByteArray {
            val ifd = buffer(2 + tags.size * 12 + 4).putShort(tags.size.toShort())
            tags.forEach { ifd.putShort(it.toShort()).putShort(3).putInt(1).putInt(0) }
            return ifd.putInt(0).array()
        }

        /** A DNG's first directory carries the DNGVersion tag, 50706. */
        fun dng(): ByteArray = buffer(8).put("II".toByteArray()).putShort(42).putInt(8).array() +
            directory(listOf(254, 256, 257, 258, 259, 262, 271, 272, 273, 277, 50_706, 50_708))

        fun cr2(): ByteArray = buffer(16).put("II".toByteArray()).putShort(42).putInt(16).put("CR".toByteArray()).put(2).put(0).putInt(0).array() +
            directory(listOf(256, 257))

        fun isoMedia(brand: String): ByteArray =
            bytes(0, 0, 0, 0x18) + "ftyp".toByteArray() + brand.toByteArray() + bytes(0, 0, 0, 0) + "mif1".toByteArray() + bytes(0, 0, 0, 8) + "free".toByteArray()

        private fun pngChunk(type: String, data: ByteArray): ByteArray {
            val crc = CRC32().apply { update(type.toByteArray() + data) }.value.toInt()
            return buffer(4, bigEndian = true).putInt(data.size).array() + type.toByteArray() + data + buffer(4, bigEndian = true).putInt(crc).array()
        }

        fun apng(): ByteArray = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            pngChunk("IHDR", buffer(13, bigEndian = true).putInt(1).putInt(1).put(8).put(6).put(0).put(0).put(0).array()) +
            pngChunk("acTL", buffer(8, bigEndian = true).putInt(2).putInt(0).array()) +
            pngChunk("IDAT", bytes(0x78, 0x9C, 0x63, 0x60, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01))

        fun oggTheora(): ByteArray = "OggS".toByteArray() + ByteArray(24) + bytes(0x80) + "theora".toByteArray() + ByteArray(32)

        fun webm(): ByteArray {
            fun element(id: ByteArray, payload: ByteArray) = id + bytes(0x80 or payload.size) + payload
            val header = element(bytes(0x42, 0x86), bytes(1)) + element(bytes(0x42, 0x82), "webm".toByteArray()) + element(bytes(0x42, 0x87), bytes(4))
            return element(bytes(0x1A, 0x45, 0xDF, 0xA3), header) + bytes(0x18, 0x53, 0x80, 0x67, 0x01) + ByteArray(16)
        }

        private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                for ((name, data) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }

        /** Flare sees an APK by its dex file, or a JAR when the v1 signature manifest comes first. */
        fun apk(signatureFirst: Boolean): ByteArray {
            val manifest = "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\n".toByteArray()
            val app = arrayOf("AndroidManifest.xml" to ByteArray(200) { 1 }, "classes.dex" to "dex\n035\u0000".toByteArray())
            return if (signatureFirst) zip(manifest, *app) else zip(*app, manifest)
        }

        fun office(mainContentType: String): ByteArray = zip(
            "[Content_Types].xml" to (
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"$mainContentType\"/></Types>"
                ).toByteArray(),
            "word/document.xml" to "<w:document/>".toByteArray(),
        )

        fun compoundFile(): ByteArray = bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) + ByteArray(504)
    }
}
