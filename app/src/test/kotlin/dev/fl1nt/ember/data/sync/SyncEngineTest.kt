package dev.fl1nt.ember.data.sync

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
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
import dev.fl1nt.ember.data.account.AccountProblem
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
import dev.fl1nt.ember.data.uploads.canRetry
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
import org.robolectric.shadows.ShadowStatFs
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
    private lateinit var repository: UploadRepository
    private lateinit var stagingRoot: File
    private val found = mutableListOf<SourceItem>()
    private val scanner = SourceScanner { found.toList() }

    @Before
    fun setUp() = runBlocking<Unit> {
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
        stagingRoot = temp.newFolder("staging")
        // Robolectric reports no free space unless told otherwise; this is about 2 GB.
        ShadowStatFs.registerStats(stagingRoot, 1_000_000, 500_000, 500_000)
        staging = UploadStaging(context, stagingRoot)
        uploader = Uploader(context, db, staging, FakeCipher(), accounts, settings, Notifier(context))
        connection = FlareConnection(accounts, OkHttpClient())
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        repository = UploadRepository(context, db, staging, FakeCipher(), WorkScheduler(context, settings))
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
        accounts = accounts,
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

    private suspend fun addSource(preset: UploadPreset = UploadPreset(), copyLink: Boolean = false, name: String = "Screenshots"): Long =
        db.sources().insert(
            SyncSourceEntity(type = SourceType.FOLDER, name = name, treeUri = "content://test/$name", sinceMillis = 0, presetJson = preset.encode(), copyLink = copyLink, createdAt = 0),
        )

    private fun run(): SyncRun = runBlocking { engine().run(mediaOnly = false, userInitiated = false, deadlineMillis = Long.MAX_VALUE) }

    @Test
    fun `uploads new files once with the source's options`() = runBlocking<Unit> {
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
    fun `temporary failures retry later without duplicating history`() = runBlocking<Unit> {
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
        // A 503 means Flare was out of reach, which doesn't use up an attempt.
        assertThat(history.single().attempts).isEqualTo(1)
    }

    @Test
    fun `files still being written are counted so a follow-up run picks them up`() = runBlocking<Unit> {
        addSource()
        val fresh = file("recording.m4a").let { it.copy(addedAtMillis = System.currentTimeMillis(), modifiedAtMillis = System.currentTimeMillis()) }
        found += fresh
        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 0, failed = 0, settling = 1))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a rejected token stops the run without marking files`() = runBlocking<Unit> {
        val id = addSource()
        found += listOf(file("one.png"), file("two.png"))
        server.enqueue(error(401, "Unauthorized"))

        assertThat(run()).isEqualTo(SyncRun.Blocked(Attention.TOKEN_REJECTED))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(db.ledger().forSource(id)).isEmpty()
        // The file waits for a reconnect instead of failing, and Home asks to reconnect.
        val waiting = db.uploads().observeRecent(10).firstValue().single()
        assertThat(waiting.status).isEqualTo(UploadStatus.QUEUED)
        assertThat(waiting.failureKind).isEqualTo(FailureKind.AUTH)
        assertThat(accounts.current()!!.problem).isEqualTo(AccountProblem.TOKEN_REJECTED)

        // Once Flare accepts the token again, the problem clears.
        server.enqueue(ok("one.png"))
        server.enqueue(ok("two.png"))
        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 2, failed = 0))
        assertThat(accounts.current()!!.problem).isNull()
    }

    @Test
    fun `a file Flare rejects is recorded and the rest continue`() = runBlocking<Unit> {
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
    fun `respects the off switch, local network access, and the run deadline`() = runBlocking<Unit> {
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
    fun `shared pastes upload from their staged copy and are cleaned up`() = runBlocking<Unit> {
        // The repository and the uploader share one staging area, as they do in the app.
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

    @Test
    fun `a file deleted before it uploads is skipped quietly`() = runBlocking<Unit> {
        val id = addSource()
        val gone = file("burst-1.png", ageMinutes = 10).also { File(temp.root, it.name).delete() }
        found += listOf(gone, file("burst-2.png", ageMinutes = 5))
        server.enqueue(ok("burst-2.png"))

        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 1, failed = 0))
        assertThat(server.requestCount).isEqualTo(1)
        // No ledger entry: a deleted file isn't listed again, and one on a drive that comes back still uploads.
        assertThat(db.ledger().get(id, "burst-1.png")).isNull()
        assertThat(db.uploads().observeRecent(10).firstValue().map { it.fileName }).containsExactly("burst-2.png")
    }

    @Test
    fun `pausing or removing a folder stops a running sync before its next file`() = runBlocking<Unit> {
        val paused = addSource(name = "Camera")
        val removed = addSource(name = "Downloads")
        found += listOf(file("one.png", ageMinutes = 10), file("two.png", ageMinutes = 5))
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                // Someone acts while the first file uploads.
                runBlocking {
                    db.sources().get(paused)?.let { db.sources().update(it.copy(enabled = false)) }
                    db.sources().delete(removed)
                }
                return ok("one.png")
            }
        }

        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 1, failed = 0))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(db.ledger().forSource(removed)).isEmpty()
    }

    @Test
    fun `Try again on a failed synced file uploads it with fresh attempts`() = runBlocking<Unit> {
        val id = addSource()
        found += file("big.png")
        server.enqueue(error(413, "Too large"))
        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 0, failed = 1))
        val failed = db.uploads().observeRecent(10).firstValue().single()
        assertThat(failed.status).isEqualTo(UploadStatus.FAILED)

        assertThat(repository.retry(failed.id)).isTrue()
        assertThat(db.ledger().get(id, "big.png")).isNull()
        server.enqueue(ok("big.png"))
        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 1, failed = 0))
        val done = db.uploads().observeRecent(10).firstValue().single()
        assertThat(done.status).isEqualTo(UploadStatus.DONE)
        assertThat(done.attempts).isEqualTo(1)
    }

    @Test
    fun `cancelling a waiting synced file keeps it from uploading`() = runBlocking<Unit> {
        val id = addSource()
        found += file("draft.png")
        server.enqueue(error(503, "Try again"))
        assertThat(run()).isInstanceOf(SyncRun.Retry::class.java)

        repository.cancel(db.uploads().observeRecent(10).firstValue().single().id)
        assertThat(db.ledger().get(id, "draft.png")!!.state).isEqualTo(LedgerState.SKIPPED)
        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 0, failed = 0))
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a file uploaded just before Ember stopped isn't uploaded again`() = runBlocking<Unit> {
        val id = addSource()
        found += file("sent.png")
        server.enqueue(ok("sent.png"))
        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 1, failed = 0))
        // Ember was stopped after Flare took the file but before the ledger heard.
        db.ledger().deleteForSource(id)

        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 0, failed = 0))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(db.ledger().get(id, "sent.png")!!.state).isEqualTo(LedgerState.UPLOADED)
    }

    @Test
    fun `temporary failures never turn synced files into failures`() = runBlocking<Unit> {
        val id = addSource()
        found += file("far-from-home.png")
        repeat(Uploader.MAX_ATTEMPTS + 2) {
            server.enqueue(error(503, "Unreachable"))
            assertThat(run()).isInstanceOf(SyncRun.Retry::class.java)
        }
        val waiting = db.uploads().observeRecent(10).firstValue().single()
        assertThat(waiting.status).isEqualTo(UploadStatus.QUEUED)
        assertThat(db.ledger().forSource(id)).isEmpty()
    }

    @Test
    fun `interrupted uploads go back to the queue and orphans of removed folders go`() = runBlocking<Unit> {
        val id = addSource()
        found += file("cut-off.png")
        server.enqueue(error(503, "Try again"))
        run()
        val row = db.uploads().observeRecent(10).firstValue().single()
        db.uploads().update(row.copy(status = UploadStatus.RUNNING))
        db.uploads().insert(listOf(row.copy(id = "orphan", syncSourceId = 999, syncItemKey = "x")))

        engine().recoverInterrupted()
        val rows = db.uploads().observeRecent(10).firstValue()
        assertThat(rows.map { it.id }).containsExactly(row.id)
        assertThat(rows.single().status).isEqualTo(UploadStatus.QUEUED)
        assertThat(db.sources().get(id)).isNotNull()
    }

    @Test
    fun `a queued file that left the folder is dropped`() = runBlocking<Unit> {
        addSource()
        found += file("moved.png")
        server.enqueue(error(503, "Try again"))
        run()
        found.clear()

        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 0, failed = 0))
        assertThat(db.uploads().observeRecent(10).firstValue()).isEmpty()
    }

    @Test
    fun `one folder that can't be listed doesn't stop the others`() = runBlocking<Unit> {
        val broken = addSource(name = "SD card")
        val missing = addSource(name = "USB drive")
        val working = addSource(name = "Screenshots")
        val shot = file("shot.png")
        val engine = SyncEngine(
            db = db,
            scanner = { source ->
                when (source.id) {
                    broken -> throw IllegalStateException("provider crashed")
                    missing -> throw FolderMissingException("gone")
                    else -> listOf(shot)
                }
            },
            uploader = uploader,
            connection = connection,
            accounts = accounts,
            settings = settings,
            notifier = Notifier(context),
            localNetworkBlocked = { false },
            mediaAccess = { MediaAccess.FULL },
        )
        server.enqueue(ok("shot.png"))

        assertThat(engine.run(mediaOnly = false, userInitiated = false, deadlineMillis = Long.MAX_VALUE)).isEqualTo(SyncRun.Completed(uploaded = 1, failed = 0))
        assertThat(db.sources().get(broken)!!.lastError).isEqualTo(SyncEngine.SCAN_ERROR)
        assertThat(db.sources().get(missing)!!.lastError).isEqualTo(SyncEngine.FOLDER_MISSING_ERROR)
        assertThat(db.sources().get(working)!!.lastError).isNull()
    }

    @Test
    fun `a refusal that would repeat pauses only that folder`() = runBlocking<Unit> {
        val id = addSource()
        found += listOf(file("first.png", ageMinutes = 10), file("second.png", ageMinutes = 5))
        server.enqueue(error(403, "Uploads are disabled for your role."))

        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 0, failed = 1))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(db.sources().get(id)!!.lastError).isEqualTo("Uploads are disabled for your role.")
        assertThat(db.ledger().get(id, "second.png")).isNull()
    }

    @Test
    fun `a password survives a failed attempt and is sent when trying again`() = runBlocking<Unit> {
        repository.enqueuePaste("secret notes", "notes.txt", UploadOrigin.PASTE, UploadPreset(), password = "hunter2")
        val queued = db.uploads().nextQueued()!!
        server.enqueue(error(413, "Too large for now"))
        assertThat(uploader.upload(queued, connection.client()!!)).isInstanceOf(Uploader.Outcome.Failed::class.java)
        val failed = db.uploads().get(queued.id)!!
        assertThat(failed.status).isEqualTo(UploadStatus.FAILED)
        assertThat(failed.sealedPassword).isNotNull()

        assertThat(repository.retry(queued.id)).isTrue()
        server.enqueue(ok("notes.txt"))
        assertThat(uploader.upload(db.uploads().get(queued.id)!!, connection.client()!!)).isInstanceOf(Uploader.Outcome.Uploaded::class.java)
        server.takeRequest()
        assertThat(server.takeRequest().body!!.utf8()).contains("name=\"password\"\r\n\r\nhunter2")
    }

    @Test
    fun `an upload whose password can't be unlocked fails instead of going out without it`() = runBlocking<Unit> {
        repository.enqueuePaste("secret notes", "notes.txt", UploadOrigin.PASTE, UploadPreset(), password = "hunter2")
        val queued = db.uploads().nextQueued()!!.copy(sealedPassword = "unreadable")

        val outcome = uploader.upload(queued, connection.client()!!)
        assertThat(outcome).isInstanceOf(Uploader.Outcome.Failed::class.java)
        assertThat(server.requestCount).isEqualTo(0)
        val failed = db.uploads().get(queued.id)!!
        assertThat(failed.failureMessage).contains("password")
        // Trying again can't fix it, so it isn't offered.
        assertThat(failed.canRetry).isFalse()
    }

    @Test
    fun `a share that failed to copy in can be copied again while the sheet is open`() = runBlocking<Unit> {
        val path = File(temp.root, "boarding-pass.png")
        val batch = repository.enqueueFiles(listOf(Uri.fromFile(path)), UploadOrigin.SHARE, UploadPreset(), password = "hunter2")
        val failed = db.uploads().observeBatch(batch).firstValue().single()
        assertThat(failed.status).isEqualTo(UploadStatus.FAILED)
        assertThat(failed.stagedPath).isNull()
        assertThat(failed.failureKind).isEqualTo(FailureKind.SOURCE_GONE)
        // Library can't retry it: Ember has no copy and no permission to read the original.
        assertThat(failed.canRetry).isFalse()
        // The failed copy left nothing behind.
        assertThat(File(stagingRoot, failed.id).exists()).isFalse()

        path.writeBytes(PNG_BYTES)
        assertThat(repository.restage(failed.id, Uri.fromFile(path), "hunter2")).isTrue()
        val queued = db.uploads().get(failed.id)!!
        assertThat(queued.status).isEqualTo(UploadStatus.QUEUED)
        assertThat(File(queued.stagedPath!!).readBytes()).isEqualTo(PNG_BYTES)
        assertThat(queued.sealedPassword).isEqualTo(FakeCipher().encrypt("hunter2"))
    }

    @Test
    fun `a file that keeps failing waits behind newer files instead of blocking them`() = runBlocking<Unit> {
        addSource()
        found += listOf(file("stubborn.png", ageMinutes = 10), file("fresh.png", ageMinutes = 5))
        server.enqueue(error(500, "Upload failed"))
        server.enqueue(ok("fresh.png"))

        // The newer file still uploads, and the run asks to try again soon.
        assertThat(run()).isEqualTo(SyncRun.Retry(uploaded = 1, reason = "Flare had a problem (HTTP 500). Try again later, or check the server logs."))
        server.takeRequest()
        assertThat(server.takeRequest().body!!.utf8()).contains("fresh.png")

        // Next time, a newer file goes first and the stubborn one waits its turn.
        found += file("newer.png", ageMinutes = 1)
        server.enqueue(ok("newer.png"))
        server.enqueue(error(500, "Upload failed"))
        run()
        assertThat(server.takeRequest().body!!.utf8()).contains("newer.png")
        val stubborn = db.uploads().observeRecent(10).firstValue().single { it.fileName == "stubborn.png" }
        assertThat(stubborn.status).isEqualTo(UploadStatus.QUEUED)
        assertThat(stubborn.attempts).isEqualTo(2)
    }

    @Test
    fun `several files failing in a row pause the sync`() = runBlocking<Unit> {
        addSource()
        found += (1..4).map { file("photo-$it.png", ageMinutes = 10L - it) }
        repeat(3) { server.enqueue(error(500, "Storage is offline")) }

        assertThat(run()).isInstanceOf(SyncRun.Retry::class.java)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(db.uploads().observeRecent(10).firstValue()).hasSize(3)
    }

    @Test
    fun `a file that fails for the same temporary reason every time counts as failed in the end`() = runBlocking<Unit> {
        val id = addSource()
        found += file("cursed.png")
        repeat(Uploader.SYNC_MAX_ATTEMPTS - 1) {
            server.enqueue(error(500, "Upload failed"))
            assertThat(run()).isInstanceOf(SyncRun.Retry::class.java)
        }
        server.enqueue(error(500, "Upload failed"))
        assertThat(run()).isEqualTo(SyncRun.Completed(uploaded = 0, failed = 1))
        assertThat(db.ledger().get(id, "cursed.png")!!.state).isEqualTo(LedgerState.FAILED)
    }

    @Test
    fun `an unreachable server doesn't use up a share's attempts`() = runBlocking<Unit> {
        repository.enqueuePaste("notes", "notes.txt", UploadOrigin.PASTE, UploadPreset())
        val id = db.uploads().nextQueued()!!.id
        repeat(Uploader.MAX_ATTEMPTS + 2) {
            server.enqueue(error(503, "Service unavailable"))
            uploader.upload(db.uploads().get(id)!!, connection.client()!!)
        }
        val waiting = db.uploads().get(id)!!
        assertThat(waiting.status).isEqualTo(UploadStatus.QUEUED)
        assertThat(waiting.attempts).isEqualTo(0)
    }

    private suspend fun <T> Flow<T>.firstValue(): T = first()
}
