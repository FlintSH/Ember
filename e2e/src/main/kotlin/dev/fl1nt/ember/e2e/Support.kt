package dev.fl1nt.ember.e2e

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Values CI passes with `am instrument -e`. */
object Args {
    private val bundle get() = InstrumentationRegistry.getArguments()

    /** The address Ember connects to. */
    val flareUrl: String get() = bundle.getString("flareUrl") ?: "http://localhost:3000"

    /** A named token with every scope. */
    val token: String get() = bundle.getString("token") ?: error("Pass -e token <flr_…>")

    val emberPackage: String get() = bundle.getString("emberPackage") ?: "dev.fl1nt.ember"

    /** Groups screenshots from different emulators. */
    val label: String get() = bundle.getString("label") ?: "api${Build.VERSION.SDK_INT}"
}

/** Checks results on the Flare server, independent of Ember's own UI. */
class FlareApi(baseUrl: String, private val token: String) {
    private val base = baseUrl.trimEnd('/').toHttpUrl()
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    private fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject {
        val url = base.newBuilder().encodedPath(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GET $path returned HTTP ${response.code}" }
            return JSONObject(response.body.string())
        }
    }

    /** Library files whose names match [search], with their MIME types. */
    fun files(search: String): Map<String, String> {
        val data = get("/api/files", mapOf("search" to search, "limit" to "100")).getJSONArray("data")
        return (0 until data.length()).associate { data.getJSONObject(it).let { file -> file.getString("name") to file.getString("mimeType") } }
    }

    fun shortLinkTargets(): List<String> {
        val urls = get("/api/urls").getJSONObject("data").getJSONArray("urls")
        return (0 until urls.length()).map { urls.getJSONObject(it).getString("targetUrl") }
    }
}

object Device {
    /** Runs a shell command as the shell user and returns its output. */
    fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun grant(packageName: String, vararg permissions: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        permissions.forEach { permission -> runCatching { automation.grantRuntimePermission(packageName, permission) } }
    }
}

/** Saves screenshots to Download/ember-e2e so CI can pull them with adb. */
class Screenshots(private val context: Context, private val label: String) {
    private var index = 0

    fun take(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        index++
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "%02d-%s.png".format(index, name))
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/ember-e2e/$label")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Creates real photos in MediaStore, owned by the test app. */
object Photos {
    fun create(context: Context, album: String, name: String, color: Int): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$album")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "MediaStore insert failed" }
        resolver.openOutputStream(uri).use { output ->
            val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(color)
                drawText(name, 24f, 160f, Paint().apply { this.color = Color.WHITE; textSize = 28f; isAntiAlias = true })
            }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, checkNotNull(output))
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
