package dev.fl1nt.ember

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.preferencesDataStore
import dev.fl1nt.ember.api.Capabilities
import dev.fl1nt.ember.api.forFlare
import dev.fl1nt.ember.data.FlareConnection
import dev.fl1nt.ember.data.UploadCopies
import dev.fl1nt.ember.data.account.AccountStore
import dev.fl1nt.ember.data.account.KeystoreSecretCipher
import dev.fl1nt.ember.data.account.SecretCipher
import dev.fl1nt.ember.data.db.EmberDatabase
import dev.fl1nt.ember.data.links.LinksRepository
import dev.fl1nt.ember.data.settings.SettingsStore
import dev.fl1nt.ember.data.sync.DefaultSourceScanner
import dev.fl1nt.ember.data.sync.DocumentTreeScanner
import dev.fl1nt.ember.data.sync.MediaAlbums
import dev.fl1nt.ember.data.sync.MediaStoreScanner
import dev.fl1nt.ember.data.sync.SyncEngine
import dev.fl1nt.ember.data.sync.SyncRepository
import dev.fl1nt.ember.data.uploads.UploadRepository
import dev.fl1nt.ember.data.uploads.UploadStaging
import dev.fl1nt.ember.data.uploads.Uploader
import dev.fl1nt.ember.system.LocalNetworkAccess
import dev.fl1nt.ember.system.MediaPermissions
import dev.fl1nt.ember.work.Notifier
import dev.fl1nt.ember.work.WorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

private val Context.accountData by preferencesDataStore(name = "account")
private val Context.settingsData by preferencesDataStore(name = "settings")

/** Wires Ember's pieces together. One instance lives in [EmberApp]. */
class AppContainer(val application: Application) {
    private val app: Context = application
    private val userAgent = "Ember/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT})"

    val http: OkHttpClient = OkHttpClient.Builder()
        .forFlare()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .build()

    val cipher: SecretCipher = KeystoreSecretCipher()
    val accounts = AccountStore(app.accountData, cipher)

    /**
     * What a connection that just succeeded can do, until the "You're connected"
     * screen is dismissed. It lives here, not in the onboarding screens, because
     * saving the account replaces those screens before they could show it.
     */
    val justConnected = MutableStateFlow<Capabilities?>(null)
    val settings = SettingsStore(app.settingsData)
    val database: EmberDatabase = EmberDatabase.create(app)
    val notifier = Notifier(app)
    val scheduler = WorkScheduler(app, settings)
    val localNetwork = LocalNetworkAccess(app)
    val uploadCopies = UploadCopies(File(app.cacheDir, "upload-copies"))
    val connection = FlareConnection(accounts, http, uploadCopies)
    val staging = UploadStaging(app)
    val uploader = Uploader(app, database, staging, cipher, accounts, settings, notifier)
    val uploads = UploadRepository(app, database, staging, cipher, scheduler)
    val links = LinksRepository(database, connection, accounts)
    val sync = SyncRepository(database, app.contentResolver, MediaAlbums(app.contentResolver), settings, scheduler, recount = { syncEngine.recount(it) })
    val syncEngine = SyncEngine(
        db = database,
        scanner = DefaultSourceScanner(MediaStoreScanner(app.contentResolver), DocumentTreeScanner(app.contentResolver)),
        uploader = uploader,
        connection = connection,
        accounts = accounts,
        settings = settings,
        notifier = notifier,
        localNetworkBlocked = localNetwork::needsPermission,
        mediaAccess = { MediaPermissions.access(app) },
    )
}
