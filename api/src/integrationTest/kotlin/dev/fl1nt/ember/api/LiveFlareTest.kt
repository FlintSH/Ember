package dev.fl1nt.ember.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

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

    private fun client(token: String, partSize: Int = FlareClient.DEFAULT_PART_SIZE, threshold: Long = FlareClient.DEFAULT_CHUNK_THRESHOLD) =
        FlareClient(FlareOrigin.parse(fixture.url), FlareCredential.of(token), http, limiter, partSize, threshold)

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
}
