package dev.fl1nt.ember.e2e

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.UUID
import java.util.regex.Pattern

/**
 * Walks through Ember the way a person would, against a real Flare server, and
 * checks each result through Flare's API. The steps share one connected app, so
 * they run in order.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EmberJourneyTest {

    companion object {
        private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
        private val context get() = instrumentation.context
        private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
        private val pkg get() = Args.emberPackage
        private val run = UUID.randomUUID().toString().take(6)
        private val api by lazy { FlareApi(Args.flareUrl, Args.token) }
        private val shots by lazy { Screenshots(context, Args.label) }
        private val album = "EmberE2E-$run"

        @BeforeClass
        @JvmStatic
        fun prepare() {
            // Start from a fresh install state, then allow what a person would allow.
            Device.shell("pm clear $pkg")
            val permissions = buildList {
                if (Build.VERSION.SDK_INT >= 33) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    add(Manifest.permission.READ_MEDIA_VIDEO)
                } else {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            Device.grant(pkg, *permissions.toTypedArray())
            // Fail fast, with the reason, when the emulator can't reach the test server.
            runCatching { api.health() }.onFailure { error ->
                throw AssertionError("This device can't reach Flare at ${Args.flareUrl}: $error", error)
            }
        }
    }

    private fun waitFor(selector: BySelector, timeout: Long = 20_000): UiObject2 =
        device.wait(Until.findObject(selector), timeout) ?: throw AssertionError("Timed out waiting for $selector\n${hierarchy()}")

    /**
     * The editable node of a labeled Ember text field. The label is a child of the
     * EditText, and only the EditText accepts text.
     */
    private fun field(label: String): UiObject2 =
        waitFor(By.clazz("android.widget.EditText").hasDescendant(By.desc(label)))

    private fun hierarchy(): String = java.io.ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString(Charsets.UTF_8.name())

    private fun waitUntil(timeout: Long = 90_000, description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            SystemClock.sleep(1_000)
        }
        throw AssertionError("Timed out waiting until $description")
    }

    private fun launchEmber() {
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(pkg)) { "$pkg is not installed" }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 15_000)
    }

    private fun shareActivity() = ComponentName(pkg, "dev.fl1nt.ember.ui.share.ShareActivity")

    /** Closes the soft keyboard if it's showing, so buttons below text fields are visible. */
    private fun hideKeyboard() {
        if (Device.shell("dumpsys input_method").contains("mInputShown=true")) {
            device.pressBack()
            device.waitForIdle()
        }
    }

    /**
     * Android previews the clipboard after a copy, over the bottom of the screen. It
     * covers the sheet's Done button, so a tap there would hit the preview instead.
     */
    private fun dismissClipboardPreview() {
        val preview = By.pkg("com.android.systemui").res(Pattern.compile(".*:id/(dismiss_button|clipboard_overlay_dismiss)"))
        val dismiss = device.wait(Until.findObject(preview), 3_000) ?: return
        dismiss.click()
        device.wait(Until.gone(preview), 5_000)
    }

    /** Scrolls until [selector] is on screen; long forms keep their button below the fold. */
    private fun scrollTo(selector: BySelector): UiObject2 {
        device.findObject(selector)?.let { return it }
        return waitFor(By.scrollable(true)).scrollUntil(Direction.DOWN, Until.findObject(selector))
            ?: throw AssertionError("Couldn't scroll to $selector\n${hierarchy()}")
    }

    private fun clickIfPresent(selector: BySelector, timeout: Long = 4_000): Boolean {
        val target = device.wait(Until.findObject(selector), timeout) ?: return false
        target.click()
        return true
    }

    @Test
    fun step1_connectsToFlare() {
        launchEmber()
        waitFor(By.text("Connect to Flare"))
        shots.take("welcome")
        device.findObject(By.text("Connect to Flare")).click()

        field("Flare address").text = Args.flareUrl
        field("API token").text = Args.token
        hideKeyboard()
        shots.take("connect")
        waitFor(By.text("Connect")).click()

        // Android 17 asks before Ember can reach a server on the local network.
        if (clickIfPresent(By.text("Continue"))) {
            shots.take("local-network")
            clickIfPresent(By.res(Pattern.compile("com.android.permissioncontroller:id/permission_allow.*button")), timeout = 10_000) ||
                clickIfPresent(By.text(Pattern.compile("Allow|ALLOW")), timeout = 5_000)
        }

        waitFor(By.text("You're connected"), timeout = 45_000)
        shots.take("connected")
        device.findObject(By.text(Pattern.compile("Allow notifications|Start using Ember"))).click()
        waitFor(By.text("Recent uploads"))
        shots.take("home-empty")
    }

    @Test
    fun step2_uploadsFromTheShareSheet() {
        val name = "e2e-share-$run.png"
        val photo = Photos.create(context, "EmberE2E-share", name, Color.rgb(255, 106, 0))
        context.startActivity(
            Intent(Intent.ACTION_SEND)
                .setComponent(shareActivity())
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, photo)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitFor(By.text("Upload 1 file"))
        shots.take("share-review")
        device.findObject(By.text("Upload 1 file")).click()

        waitFor(By.text("Done. Link copied."), timeout = 60_000)
        dismissClipboardPreview()
        shots.take("share-done")
        assertThat(api.files(name)).containsEntry(name, "image/png")
        scrollTo(By.text("Done")).click()
    }

    @Test
    fun step3_pastesSelectedText() {
        val name = "e2e-paste-$run.log"
        context.startActivity(
            Intent(Intent.ACTION_PROCESS_TEXT)
                .setComponent(ComponentName(pkg, "dev.fl1nt.ember.ui.share.ProcessTextAlias"))
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, "2026-10-23T21:45:12Z WARN upload failed: storage quota exceeded\n2026-10-23T21:45:13Z INFO retrying in 60s")
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitFor(By.text("Create paste"))
        field("File name").text = name
        hideKeyboard()
        shots.take("paste-review")
        waitFor(By.text("Create paste")).click()

        waitFor(By.text("Done. Link copied."), timeout = 60_000)
        dismissClipboardPreview()
        shots.take("paste-done")
        assertThat(api.files(name)).containsEntry(name, "text/plain")
        scrollTo(By.text("Done")).click()
    }

    @Test
    fun step4_shortensASharedLink() {
        val target = "https://example.com/ember-e2e/$run"
        context.startActivity(
            Intent(Intent.ACTION_SEND)
                .setComponent(shareActivity())
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitFor(By.text("Shorten link"))
        shots.take("shorten-review")
        device.findObject(By.text("Shorten link")).click()

        waitFor(By.text("Done. Link copied."), timeout = 30_000)
        dismissClipboardPreview()
        shots.take("shorten-done")
        assertThat(api.shortLinkTargets()).contains(target)
        scrollTo(By.text("Done")).click()
    }

    @Test
    fun step5_syncsANewAlbum() {
        val names = listOf("e2e-sync-$run-1.png", "e2e-sync-$run-2.png")
        Photos.create(context, album, names[0], Color.rgb(255, 195, 1))
        Photos.create(context, album, names[1], Color.rgb(255, 43, 1))

        launchEmber()
        waitFor(By.text("Sync")).click()
        waitFor(By.text("Add a folder")).click()
        waitFor(By.text(album), timeout = 20_000)
        shots.take("sync-albums")
        device.findObject(By.text(album)).click()

        waitFor(By.text(Pattern.compile("All 2 items"))).click()
        shots.take("sync-options")
        scrollTo(By.text("Start syncing")).click()

        waitUntil(description = "Flare has both synced photos") { api.files("e2e-sync-$run").keys.containsAll(names) }
        waitFor(By.text("2 uploaded"), timeout = 30_000)
        shots.take("sync-source")
    }

    @Test
    fun step6_browsesTheLibrary() {
        device.pressBack()
        waitFor(By.text("Library")).click()
        waitFor(By.text("Recent"))
        shots.take("library-recent")
        device.findObject(By.text("Files")).click()
        waitFor(By.text("e2e-share-$run.png"), timeout = 20_000)
        shots.take("library-files")
        device.findObject(By.text("Links")).click()
        waitFor(By.text(Pattern.compile(".*/u/.*")), timeout = 20_000)
        shots.take("library-links")

        waitFor(By.text("Home")).click()
        waitFor(By.text("Recent uploads"))
        shots.take("home")
        waitFor(By.text("Settings")).click()
        waitFor(By.text("Connection"))
        shots.take("settings")
    }
}
