package dev.fl1nt.ember.system

import android.content.Context
import android.database.sqlite.SQLiteFullException
import dev.fl1nt.ember.R
import dev.fl1nt.ember.api.FlareException
import dev.fl1nt.ember.data.uploads.InsufficientSpaceException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Explains a failure in plain words, with the next step when there is one.
 * [host] names the server in network errors.
 */
fun describeError(context: Context, error: Throwable, host: String? = null): String {
    val place = host ?: context.getString(R.string.error_your_server)
    return when (error) {
        is FlareException.Unauthorized -> context.getString(R.string.error_unauthorized)
        is FlareException.Forbidden -> error.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.error_forbidden)
        is FlareException.TooLarge -> error.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.error_too_large)
        is FlareException.RateLimited -> error.retryAfterSeconds.toInt().let { context.resources.getQuantityString(R.plurals.error_rate_limited, it, it) }
        is FlareException.UnexpectedResponse -> context.getString(R.string.error_not_flare, place)
        is FlareException.Server -> context.getString(R.string.error_server, error.status)
        is FlareException.Network -> networkMessage(context, error.cause, place)
        is FlareException.SourceUnavailable ->
            context.getString(if (error.cause is SecurityException) R.string.error_file_access else R.string.error_file_gone)
        is FlareException -> error.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.error_generic)
        // Ember's own problems, so they don't read like network trouble.
        is InsufficientSpaceException, is SQLiteFullException -> context.getString(R.string.error_no_space)
        is GeneralSecurityException -> context.getString(R.string.error_secure_storage)
        is FileNotFoundException -> context.getString(R.string.error_file_gone)
        is SecurityException -> context.getString(R.string.error_file_access)
        is IOException -> context.getString(R.string.error_read)
        else -> context.getString(R.string.error_generic)
    }
}

private fun networkMessage(context: Context, cause: Throwable?, place: String): String {
    val root = generateSequence(cause) { it.cause }.toList()
    return when {
        root.any { it is SSLException || it is CertificateException } -> context.getString(R.string.error_certificate, place)
        root.any { it is UnknownHostException } -> context.getString(R.string.error_unknown_host, place)
        root.any { it is SocketTimeoutException } -> context.getString(R.string.error_timeout, place)
        root.any { it is ConnectException } -> context.getString(R.string.error_refused, place)
        else -> context.getString(R.string.error_network, place)
    }
}
