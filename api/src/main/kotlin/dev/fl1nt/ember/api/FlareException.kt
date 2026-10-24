package dev.fl1nt.ember.api

import java.io.IOException

/**
 * A failed Flare request.
 *
 * [message] is the server's own `error` text when Flare supplied one, so it can
 * be shown to people as-is. Subclasses describe what Ember can do about it.
 */
sealed class FlareException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Whether repeating the same request later might succeed. */
    open val isRetryable: Boolean get() = false

    /** The device couldn't reach the server, or the connection dropped. */
    class Network(cause: IOException) :
        FlareException(cause.message ?: "The connection to Flare failed.", cause) {
        override val isRetryable: Boolean get() = true
    }

    /**
     * Flare's generic authentication failure. Missing, unknown, expired, or revoked
     * tokens, missing scopes, and required email verification all look the same.
     */
    class Unauthorized(message: String = "Flare didn't accept this token.") : FlareException(message)

    /** Authenticated, but the account's roles or a bound upload profile forbid it. */
    class Forbidden(message: String) : FlareException(message)

    class NotFound(message: String) : FlareException(message)

    class BadRequest(message: String) : FlareException(message)

    /** The file is bigger than the instance allows, or the storage quota is full. */
    class TooLarge(message: String) : FlareException(message)

    /** Storage changed during a chunked upload, or a part arrived after completion. */
    class Conflict(message: String) : FlareException(message)

    /** Flare's upload limiter allows 30 upload starts per minute per address. */
    class RateLimited(val retryAfterSeconds: Long) :
        FlareException("Flare is limiting uploads. Retrying in ${retryAfterSeconds}s.") {
        override val isRetryable: Boolean get() = true
    }

    /**
     * A server-side failure. Outages, restarts, gateway errors, and timeouts are
     * worth retrying; Flare cleans up an upload it couldn't commit, so a retry
     * doesn't create a duplicate.
     */
    class Server(val status: Int, message: String) : FlareException(message) {
        override val isRetryable: Boolean get() = status in RETRYABLE_STATUSES

        private companion object {
            // 408 Request Timeout, 425 Too Early, 5xx outages, Wi-Fi sign-in pages (511),
            // and Cloudflare's origin errors.
            val RETRYABLE_STATUSES = setOf(408, 425, 500, 502, 503, 504, 511, 520, 521, 522, 523, 524, 530)
        }
    }

    /** The file to upload couldn't be read, for example because it was deleted. */
    class SourceUnavailable(message: String, cause: Throwable) : FlareException(message, cause)

    /** The server answered, but not in a way Flare would. */
    class UnexpectedResponse(message: String, cause: Throwable? = null) : FlareException(message, cause)
}
