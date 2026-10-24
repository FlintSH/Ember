package dev.fl1nt.ember.data.sync

import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.fl1nt.ember.FakeCipher
import dev.fl1nt.ember.PNG_BYTES
import dev.fl1nt.ember.api.Access
import dev.fl1nt.ember.api.Capabilities
import dev.fl1nt.ember.api.FlareCredential
import dev.fl1nt.ember.api.FlareOrigin
import dev.fl1nt.ember.api.Visibility
import dev.fl1nt.ember.data.FlareConnection
import dev.fl1nt.ember.data.account.AccountStore
import dev.fl1nt.ember.data.db.EmberDatabase
import dev.fl1nt.ember.data.db.FailureKind
import dev.fl1nt.ember.data.db.LedgerState
import dev.fl1nt.ember.data.db.SourceType
import dev.fl1nt.ember.data.db.SyncSourceEntity
import dev.fl1nt.ember.data.db.UploadOrigin
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.data.settings.SettingsStore
import dev.fl1nt.ember.data.settings.UploadPreset
import dev.fl1nt.ember.data.uploads.UploadRepository
import dev.fl1nt.ember.data.uploads.UploadStaging
import dev.fl1nt.ember.data.uploads.Uploader
import dev.fl1nt.ember.system.MediaAccess
import dev.fl1nt.ember.testDataStore
import dev.fl1nt.ember.work.Attention
import dev.fl1nt.ember.work.Notifier
import dev.fl1nt.ember.work.WorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Runs the sync engine and uploader end to end against a fake Flare server. */
@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var db: EmberDatabase
    private lateinit var accounts: AccountStore
    private lateinit var settings: SettingsStore
    private lateinit var uploader: Uploader
    private lateinit var staging: UploadStaging
    private lateinit var connection: FlareConnection
    private val found = mutableListOf<SourceItem>()
    private val scanner = SourceScanner { found.toList() }

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer().apply { start() }
        db = Room.inMemoryDatabaseBuilder(context, EmberDatabase::class.java).allowMainThreadQueries().build()
        accounts = AccountStore(testDataStore(temp.newFolder()), FakeCipher())
        settings = SettingsStore(testDataStore(temp.newFolder()))
        accounts.connect(
            FlareOrigin.parse(server.url("/").toString()),
            FlareCredential.of("flr_test"),
            Capabilities(Access.GRANTED, Access.GRANTED, Access.GRANTED, Access.GRANTED, Access.NOT_IN_TOKEN),
            displayName = "Test Flare",
        )
        staging = UploadStaging(context, temp.newFolder("staging"))
        uploader = Uploader(context, db, staging, FakeCipher(), accounts, settings, Notifier(context))
        connection = FlareConnection(accounts, OkHttpClient())
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun engine(blocked: Boolean = false, media: MediaAccess = MediaAccess.FULL) = SyncEngine(
        db = db,
        scanner = scanner,
        uploader = uploader,
        connection = connection,
        settings = settings,
        notifier = Notifier(context),
        localNetworkBlocked = { blocked },
        mediaAccess = { media },
    )

    private fun ok(name: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(
        """{"success":true,"data":{"pageUrl":"https://files.example.com/alex/$name","rawUrl":"https://files.example.com/api/files/u/$name","name":"$name","size":${PNG_BYTES.size},"type":"image/png"}}""",
    ).build()

    private fun error(code: Int, message: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body("""{"success":false,"error":"$message"}""").build()

    /** A file on disk; a larger [ageMinutes] sorts earlier because sync uploads oldest first. */
    private fun file(name: String, ageMinutes: Long = 2): SourceItem {
        val file = File(temp.root, name).apply { writeBytes(PNG_BYTES) }
        val time = System.currentTimeMillis() - ageMinutes * 60_000
        return SourceItem(name, name, "image/png", file.length(), file.toURI().toString(), time, time)
    }

    private suspend fun addSource(preset: UploadPreset = UploadPreset(), copyLink: Boolean = false): Long =
        db.sources().insert(
            SyncSourceEntity(type = SourceType.FOLDER, name = "Screenshots", treeUri = "content://test/tree", sinceMillis = 0, presetJson = preset.encode(), copyLink = copyLink, createdAt = 0),
        )

    private fun run() = runBlocking { engine().run(mediaOnly = false, userInitiated = false, deadlineMillis = Long.MAX_VALUE) }

    @Test
    fun `uploads new files once with the source's options`() = runBlocking {
        val id = addSource(UploadPreset(visibility = Visibility.PRIVATE, folder = UploadPreset.Choice.Id("folder-9")), copyLink = true)
        found += listOf(file("a.png"), file("b.png"))
        server.enqueue(ok("a.png"))
        server.enqueue(ok("b.png"))

        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 2, failed = 0))

        val requests = (1..2).map { server.takeRequest() }
        assertThat(requests.map { it.headers["X-Upload-Folder"] }).containsExactly("folder-9", "folder-9")
        assertThat(requests.first().body!!.utf8()).contains("PRIVATE")
        assertThat(db.ledger().forSource(id).map { it.state }).containsExactly(LedgerState.UPLOADED, LedgerState.UPLOADED)
        val uploads = db.uploads().observeRecent(10).firstValue()
        assertThat(uploads.map { it.status }.distinct()).containsExactly(UploadStatus.DONE)
        assertThat(uploads.map { it.origin }.distinct()).containsExactly(UploadOrigin.SYNC)
        // Copy-link sources put the newest link on the clipboard, like a screenshot tool.
        assertThat(context.getSystemService<ClipboardManager>()!!.primaryClip!!.getItemAt(0).text.toString()).endsWith(".png")

        // A second run finds nothing new and sends nothing.
        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 0, failed = 0))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `temporary failures retry later without duplicating history`() = runBlocking {
        val id = addSource()
        found += file("flaky.png")
        server.enqueue(error(503, "Try again"))

        assertThat(run()).isInstanceOf(SyncRun.Retry::class.java)
        assertThat(db.ledger().forSource(id)).isEmpty()
        val waiting = db.uploads().observeRecent(10).firstValue().single()
        assertThat(waiting.status).isEqualTo(UploadStatus.QUEUED)
        assertThat(waiting.failureKind).isEqualTo(FailureKind.TRANSIENT)

        server.enqueue(ok("flaky.png"))
        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 1, failed = 0))
        val history = db.uploads().observeRecent(10).firstValue()
        assertThat(history).hasSize(1)
        assertThat(history.single().status).isEqualTo(UploadStatus.DONE)
        assertThat(history.single().attempts).isEqualTo(2)
    }

    @Test
    fun `files still being written are counted so a follow-up run picks them up`() = runBlocking {
        addSource()
        val fresh = file("recording.m4a").let { it.copy(addedAtMillis = System.currentTimeMillis(), modifiedAtMillis = System.currentTimeMillis()) }
        found += fresh
        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 0, failed = 0, settling = 1))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a rejected token stops the run without marking files`() = runBlocking {
        val id = addSource()
        found += listOf(file("one.png"), file("two.png"))
        server.enqueue(error(401, "Unauthorized"))

        assertThat(run()).isEqualTo(SyncRun.Blocked(Attention.TOKEN_REJECTED))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(db.ledger().forSource(id)).isEmpty()
    }

    @Test
    fun `a file Flare rejects is recorded and the rest continue`() = runBlocking {
        val id = addSource()
        found += listOf(file("huge.png", ageMinutes = 10), file("fine.png", ageMinutes = 5))
        server.enqueue(error(413, "The file exceeds the upload size limit."))
        server.enqueue(ok("fine.png"))

        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 1, failed = 1))
        val ledger = db.ledger().forSource(id).associateBy { it.itemKey }
        assertThat(ledger["huge.png"]!!.state).isEqualTo(LedgerState.FAILED)
        assertThat(ledger["huge.png"]!!.message).isEqualTo("The file exceeds the upload size limit.")
        assertThat(ledger["fine.png"]!!.state).isEqualTo(LedgerState.UPLOADED)

        // "Try failed files again" clears the failure so the next run uploads it.
        db.ledger().clearFailures(id)
        server.enqueue(ok("huge.png"))
        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 1, failed = 0))
    }

    @Test
    fun `respects the off switch, local network access, and the run deadline`() = runBlocking {
        addSource()
        found += listOf(file("x.png"), file("y.png"))
        settings.update { it.copy(sync = it.sync.copy(enabled = false)) }
        assertThat(run()).isEqualTo(SyncRun.Disabled)

        assertThat(engine(blocked = true).run(mediaOnly = false, userInitiated = true, deadlineMillis = Long.MAX_VALUE))
            .isEqualTo(SyncRun.Blocked(Attention.LOCAL_NETWORK))

        val paused = engine().run(mediaOnly = false, userInitiated = true, deadlineMillis = 0)
        assertThat(paused).isEqualTo(SyncRun.Paused(uploaded = 0, remaining = 2))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `shared pastes upload from their staged copy and are cleaned up`() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        // The repository and the uploader share one staging area, as they do in the app.
        val repository = UploadRepository(db, staging, FakeCipher(), WorkScheduler(context, settings))
        repository.enqueuePaste("# notes", "notes.md", UploadOrigin.TEXT_SELECTION, UploadPreset(), password = "pw")
        val queued = db.uploads().nextQueued()!!
        assertThat(File(queued.stagedPath!!).readText()).isEqualTo("# notes")
        assertThat(queued.sealedPassword).isNotNull()

        server.enqueue(ok("notes.md"))
        val outcome = uploader.upload(queued, connection.client()!!)
        assertThat(outcome).isInstanceOf(Uploader.Outcome.Uploaded::class.java)

        val request = server.takeRequest()
        assertThat(request.headers["X-Flare-Paste"]).isEqualTo("true")
        assertThat(request.body!!.utf8()).contains("name=\"password\"\r\n\r\npw")
        val done = db.uploads().get(queued.id)!!
        assertThat(done.status).isEqualTo(UploadStatus.DONE)
        assertThat(done.sealedPassword).isNull()
        assertThat(done.stagedPath).isNull()
        assertThat(File(queued.stagedPath).exists()).isFalse()
        assertThat(accounts.current()!!.linkOrigin.toString()).isEqualTo("https://files.example.com")
    }

    private suspend fun <T> Flow<T>.firstValue(): T = first()
}
