package dev.fl1nt.ember.data

import dev.fl1nt.ember.api.FlareClient
import dev.fl1nt.ember.api.FlareCredential
import dev.fl1nt.ember.api.FlareOrigin
import dev.fl1nt.ember.api.UploadStartLimiter
import dev.fl1nt.ember.data.account.AccountStore
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds [FlareClient]s for the connected account. Every client for the same
 * instance shares one [UploadStartLimiter], so share uploads and background
 * sync together stay under Flare's upload rate limit.
 */
class FlareConnection(
    private val accounts: AccountStore,
    private val http: OkHttpClient,
) {
    private val limiters = ConcurrentHashMap<String, UploadStartLimiter>()

    /** A client for the connected account, or `null` when Ember isn't connected. */
    suspend fun client(): FlareClient? {
        val account = accounts.current() ?: return null
        val credential = accounts.credential() ?: return null
        return clientFor(account.origin, credential)
    }

    fun clientFor(origin: FlareOrigin, credential: FlareCredential): FlareClient =
        FlareClient(origin, credential, http, limiters.getOrPut(origin.toString()) { UploadStartLimiter() })
}
