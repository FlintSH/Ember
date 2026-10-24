package dev.fl1nt.ember.data

import android.os.Process
import dev.fl1nt.ember.api.FlareClient
import dev.fl1nt.ember.api.FlareCredential
import dev.fl1nt.ember.api.FlareOrigin
import dev.fl1nt.ember.api.UploadStartLimiter
import dev.fl1nt.ember.data.account.AccountStore
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds [FlareClient]s for the connected account. Every client for the same
 * instance shares one [UploadStartLimiter], so share uploads and background
 * sync together stay under Flare's upload rate limit.
 *
 * [uploadCopies] holds the copies clients make of files whose size isn't known
 * up front. See [UploadCopies].
 */
class FlareConnection(
    private val accounts: AccountStore,
    private val http: OkHttpClient,
    private val uploadCopies: UploadCopies? = null,
) {
    private val limiters = ConcurrentHashMap<String, UploadStartLimiter>()

    /** A client for the connected account, or `null` when Ember isn't connected. */
    suspend fun client(): FlareClient? {
        val account = accounts.current() ?: return null
        val credential = accounts.credential() ?: return null
        return clientFor(account.origin, credential)
    }

    fun clientFor(origin: FlareOrigin, credential: FlareCredential): FlareClient = FlareClient(
        origin = origin,
        credential = credential,
        http = http,
        limiter = limiters.getOrPut(origin.toString()) { UploadStartLimiter() },
        tempDirectory = uploadCopies?.directory(),
    )
}

/**
 * Where uploads copy files of unknown size. A client deletes its copy when it's
 * done, but not if the process dies first, so each process gets its own folder
 * and [clearLeftovers] removes the folders of processes that are gone.
 */
class UploadCopies(private val root: File) {
    private val mine = File(root, Process.myPid().toString())

    fun directory(): File = mine.apply { mkdirs() }

    fun clearLeftovers() {
        root.listFiles()?.filter { it != mine }?.forEach { it.deleteRecursively() }
    }
}
