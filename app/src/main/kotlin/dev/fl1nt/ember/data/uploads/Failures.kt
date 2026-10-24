package dev.fl1nt.ember.data.uploads

import dev.fl1nt.ember.api.FlareException
import dev.fl1nt.ember.data.db.FailureKind
import kotlinx.serialization.SerializationException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A failure Ember can explain, and whether trying again later might work. */
data class Failure(
    val kind: FailureKind,
    val message: String,
    /** Flare couldn't be reached at all, so no other file would get through right now either. */
    val unreachable: Boolean = false,
) {
    val isTransient: Boolean get() = kind == FailureKind.TRANSIENT

    companion object {
        /** Classifies [error]. [message] is what people see; see [dev.fl1nt.ember.system.describeError]. */
        fun of(error: Throwable, message: String): Failure = Failure(kindOf(error), message, isUnreachable(error))

        fun kindOf(error: Throwable): FailureKind = when (error) {
            is FlareException.Unauthorized -> FailureKind.AUTH
            is FlareException.Forbidden -> FailureKind.PERMISSION
            is FlareException.TooLarge -> FailureKind.TOO_LARGE
            is FlareException.BadRequest, is FlareException.NotFound -> FailureKind.REJECTED
            is FlareException.SourceUnavailable ->
                if (error.cause is SecurityException) FailureKind.NO_ACCESS else FailureKind.SOURCE_GONE
            // Storage changed under a chunked upload; starting over works.
            is FlareException.Conflict -> FailureKind.TRANSIENT
            is FlareException -> if (error.isRetryable) FailureKind.TRANSIENT else FailureKind.OTHER
            is InsufficientSpaceException -> FailureKind.OTHER
            is FileNotFoundException -> FailureKind.SOURCE_GONE
            is SecurityException -> FailureKind.NO_ACCESS
            is IOException -> FailureKind.TRANSIENT
            else -> FailureKind.OTHER
        }

        /**
         * True when the request never reached a working Flare: the connection was refused
         * or timed out, the name didn't resolve, a gateway or Wi-Fi sign-in page answered
         * instead, or Flare's rate limit applies. Other temporary failures, like a 500 or
         * a read error, may be about one file.
         */
        fun isUnreachable(error: Throwable): Boolean = when (error) {
            is FlareException.RateLimited -> true
            is FlareException.Server -> error.status in GATEWAY_STATUSES
            is FlareException.Network -> generateSequence(error.cause) { it.cause }.any { cause ->
                cause is ConnectException || cause is NoRouteToHostException || cause is UnknownHostException ||
                    cause is PortUnreachableException ||
                    (cause is SocketTimeoutException && cause.message?.contains("connect", ignoreCase = true) == true) ||
                    // Something other than Flare answered, such as a Wi-Fi sign-in page.
                    cause is SerializationException
            }
            else -> false
        }

        private val GATEWAY_STATUSES = setOf(502, 503, 504, 511, 520, 521, 522, 523, 524, 530)
    }
}
