package dev.fl1nt.ember.data.uploads

import dev.fl1nt.ember.api.FlareException
import dev.fl1nt.ember.data.db.FailureKind
import java.io.FileNotFoundException
import java.io.IOException

/** A failure Ember can explain, and whether trying again later might work. */
data class Failure(val kind: FailureKind, val message: String) {
    val isTransient: Boolean get() = kind == FailureKind.TRANSIENT

    companion object {
        fun of(error: Throwable): Failure = when (error) {
            is FlareException.Unauthorized -> Failure(FailureKind.AUTH, error.message.orEmpty())
            is FlareException.Forbidden -> Failure(FailureKind.PERMISSION, error.message.orEmpty())
            is FlareException.TooLarge -> Failure(FailureKind.TOO_LARGE, error.message.orEmpty())
            is FlareException.BadRequest, is FlareException.NotFound -> Failure(FailureKind.REJECTED, error.message.orEmpty())
            is FlareException.Conflict -> Failure(FailureKind.TRANSIENT, error.message.orEmpty())
            is FlareException.Network, is FlareException.RateLimited -> Failure(FailureKind.TRANSIENT, error.message.orEmpty())
            is FlareException.Server ->
                Failure(if (error.isRetryable) FailureKind.TRANSIENT else FailureKind.OTHER, error.message.orEmpty())
            is FlareException.UnexpectedResponse -> Failure(FailureKind.OTHER, error.message.orEmpty())
            is FlareException.SourceUnavailable -> Failure(FailureKind.SOURCE_GONE, error.message.orEmpty())
            is InsufficientSpaceException -> Failure(FailureKind.OTHER, error.message.orEmpty())
            is FileNotFoundException, is SecurityException -> Failure(FailureKind.SOURCE_GONE, error.message.orEmpty())
            is IOException -> Failure(FailureKind.TRANSIENT, error.message.orEmpty())
            else -> Failure(FailureKind.OTHER, error.message ?: error.javaClass.simpleName)
        }
    }
}
