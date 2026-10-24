package dev.fl1nt.ember.data.uploads

import dev.fl1nt.ember.api.FlareException
import dev.fl1nt.ember.data.db.FailureKind
import java.io.FileNotFoundException
import java.io.IOException

/** A failure Ember can explain, and whether trying again later might work. */
data class Failure(val kind: FailureKind, val message: String) {
    val isTransient: Boolean get() = kind == FailureKind.TRANSIENT

    companion object {
        /** Classifies [error]. [message] is what people see; see [dev.fl1nt.ember.system.describeError]. */
        fun of(error: Throwable, message: String): Failure = Failure(kindOf(error), message)

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
    }
}
