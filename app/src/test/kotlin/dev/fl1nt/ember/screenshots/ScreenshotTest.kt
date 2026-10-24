package dev.fl1nt.ember.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.fl1nt.ember.api.Expiration
import dev.fl1nt.ember.api.Visibility
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.data.settings.EmberSettings
import dev.fl1nt.ember.data.settings.UploadPreset
import dev.fl1nt.ember.data.sync.NewSource
import dev.fl1nt.ember.data.sync.SyncProgress
import dev.fl1nt.ember.system.MediaAccess
import dev.fl1nt.ember.ui.home.HomeScreen
import dev.fl1nt.ember.ui.home.HomeState
import dev.fl1nt.ember.ui.library.LibraryScreen
import dev.fl1nt.ember.ui.library.LibraryState
import dev.fl1nt.ember.ui.library.LibraryTab
import dev.fl1nt.ember.ui.library.LinksState
import dev.fl1nt.ember.ui.library.RemoteFiles
import dev.fl1nt.ember.ui.links.ShortenScreen
import dev.fl1nt.ember.ui.links.ShortenState
import dev.fl1nt.ember.ui.onboarding.ConnectScreen
import dev.fl1nt.ember.ui.onboarding.ConnectState
import dev.fl1nt.ember.ui.onboarding.ReadyScreen
import dev.fl1nt.ember.ui.onboarding.WelcomeScreen
import dev.fl1nt.ember.ui.paste.PasteScreen
import dev.fl1nt.ember.ui.paste.PasteState
import dev.fl1nt.ember.ui.settings.SettingsScreen
import dev.fl1nt.ember.ui.settings.SettingsState
import dev.fl1nt.ember.ui.share.ShareCallbacks
import dev.fl1nt.ember.ui.share.SharePhase
import dev.fl1nt.ember.ui.share.ShareRequest
import dev.fl1nt.ember.ui.share.ShareScreen
import dev.fl1nt.ember.ui.share.ShareState
import dev.fl1nt.ember.ui.share.SharedFile
import dev.fl1nt.ember.ui.share.TextAction
import dev.fl1nt.ember.ui.sync.AddSourceScreen
import dev.fl1nt.ember.ui.sync.AddSourceState
import dev.fl1nt.ember.ui.sync.SourceOptions
import dev.fl1nt.ember.ui.sync.SyncScreen
import dev.fl1nt.ember.ui.sync.SyncState
import dev.fl1nt.ember.ui.theme.EmberTheme
import dev.fl1nt.ember.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders Ember's screens with demonstration data. Record new images with
 * `./gradlew :app:recordRoborazziDebug`; CI verifies them with
 * `verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun snap(name: String, mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
        compose.setContent {
            EmberTheme(mode) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val home = HomeState(
        account = SampleData.account,
        recent = SampleData.uploads.take(5),
        activeUploads = 1,
        sources = SampleData.sources,
        syncEnabled = true,
        syncRunning = true,
        syncProgress = SyncProgress(done = 0, total = 2, current = "Screenshot_20261023_214512.png"),
    )

    private val noop = ShareCallbacks({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun share(state: ShareState) = state.copy(account = SampleData.account, settings = EmberSettings())

    @Test
    fun welcome() = snap("welcome") { WelcomeScreen(onConnect = {}) }

    @Test
    fun connect() = snap("connect") {
        ConnectScreen(
            state = ConnectState(address = "files.northstar.example", token = "flr_demo-token-not-real", error = "Flare didn't accept this token. Check that you copied all of it and that it hasn't expired or been revoked."),
            onBack = {}, onAddressChange = {}, onTokenChange = {}, onPasteToken = {}, onToggleToken = {}, onConnect = {}, onHelp = {},
        )
    }

    @Test
    fun ready() = snap("ready") { ReadyScreen(SampleData.capabilities, "files.northstar.example", onDone = {}) }

    @Test
    fun homeDark() = snap("home") { HomeScreen(home, {}, {}, {}, {}, {}, {}, {}, {}) }

    @Test
    fun homeLight() = snap("home-light", ThemeMode.LIGHT) { HomeScreen(home, {}, {}, {}, {}, {}, {}, {}, {}) }

    @Test
    fun libraryRecent() = snap("library-recent") {
        LibraryScreen(LibraryState(LibraryTab.RECENT, SampleData.account, SampleData.uploads), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun libraryFiles() = snap("library-files") {
        LibraryScreen(
            LibraryState(LibraryTab.FILES, SampleData.account, files = RemoteFiles(items = SampleData.remoteFiles, page = 1, endReached = true)),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun libraryLinks() = snap("library-links") {
        LibraryScreen(LibraryState(LibraryTab.LINKS, SampleData.account, links = SampleData.links, linksState = LinksState(fromServer = true)), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun sync() = snap("sync") {
        SyncScreen(SyncState(SampleData.summaries, running = false, mediaAccess = MediaAccess.FULL, loaded = true), {}, { _, _ -> }, {}, {}, {}, {})
    }

    @Test
    fun syncEmpty() = snap("sync-empty") { SyncScreen(SyncState(loaded = true), {}, { _, _ -> }, {}, {}, {}, {}) }

    @Test
    fun addSourceAlbums() = snap("add-source") {
        AddSourceScreen(AddSourceState(albums = SampleData.albums, albumsLoading = false, mediaAccess = MediaAccess.FULL), {}, {}, {}, {}, {}, {})
    }

    @Test
    fun addSourceOptions() = snap("add-source-options") {
        AddSourceScreen(
            AddSourceState(
                albums = SampleData.albums,
                albumsLoading = false,
                mediaAccess = MediaAccess.FULL,
                selected = NewSource.Album(SampleData.albums[1]),
                options = SourceOptions(copyLink = true, notifyEach = true, preset = UploadPreset(folder = UploadPreset.Choice.Id("a", "Screenshots"))),
                folders = SampleData.folders,
            ),
            {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun paste() = snap("paste") {
        PasteScreen(
            PasteState(content = "server {\n    listen 443 ssl;\n    server_name files.northstar.example;\n    client_max_body_size 512m;\n}\n", fileName = "nginx.conf", visibility = Visibility.PUBLIC, expiration = Expiration.WEEK),
            {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun pasteDone() = snap("paste-done") {
        PasteScreen(PasteState(content = "x", upload = SampleData.uploads[1].copy(status = UploadStatus.DONE)), {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun shortenDone() = snap("shorten-done") {
        ShortenScreen(ShortenState(url = SampleData.links[0].targetUrl, result = SampleData.links[0], copied = true), {}, {}, {}, {}, {})
    }

    @Test
    fun settings() = snap("settings") { SettingsScreen(SettingsState(SampleData.account, EmberSettings(), SampleData.folders), {}, {}, {}, {}) }

    @Test
    fun settingsLight() = snap("settings-light", ThemeMode.LIGHT) { SettingsScreen(SettingsState(SampleData.account, EmberSettings(), SampleData.folders), {}, {}, {}, {}) }

    @Test
    fun shareFiles() = snap("share-files") {
        ShareScreen(
            share(
                ShareState(
                    phase = SharePhase.REVIEW_FILES,
                    files = listOf(
                        SharedFile(SampleData.sharedUri, "IMG_20261023_183344.jpg", 3_400_000, "image/jpeg"),
                        SharedFile(SampleData.sharedUri, "boarding-pass.pdf", 182_000, "application/pdf"),
                    ),
                    folders = SampleData.folders,
                ),
            ),
            noop,
        )
    }

    @Test
    fun shareUploading() = snap("share-uploading") {
        ShareScreen(share(ShareState(phase = SharePhase.UPLOADING, uploads = SampleData.uploads.take(1))), noop)
    }

    @Test
    fun shareDone() = snap("share-done") {
        ShareScreen(share(ShareState(phase = SharePhase.DONE, uploads = listOf(SampleData.uploads[3]), copied = true)), noop)
    }

    @Test
    fun shareText() = snap("share-text") {
        ShareScreen(
            share(
                ShareState(
                    phase = SharePhase.REVIEW_TEXT,
                    text = "2026-10-23T21:45:12Z WARN upload failed: storage quota exceeded for user alex\n2026-10-23T21:45:13Z INFO retrying in 60s",
                    textMode = ShareRequest.TextMode.SELECTION,
                    readOnlySelection = false,
                    textAction = TextAction.PASTE,
                    pasteName = "upload.log",
                ),
            ),
            noop,
        )
    }

    @Test
    fun shareShorten() = snap("share-shorten") {
        ShareScreen(share(ShareState(phase = SharePhase.REVIEW_TEXT, text = SampleData.links[2].targetUrl, url = SampleData.links[2].targetUrl, textAction = TextAction.SHORTEN)), noop)
    }
}
