package dev.fl1nt.ember.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The origin of a Flare instance, such as `https://files.example.com`.
 *
 * People often paste a dashboard address (`https://files.example.com/dashboard`)
 * or leave out the scheme. [parse] accepts both and keeps only the scheme, host,
 * and port, because Flare's API lives at the root of its origin.
 */
class FlareOrigin private constructor(val url: HttpUrl) {

    val isHttps: Boolean get() = url.isHttps

    val host: String get() = url.host

    /** The host, plus the port when it is not the scheme's default. */
    val displayName: String
        get() = if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"

    /** Resolves an absolute API path, such as `/api/files`, against this origin. */
    fun resolve(path: String): HttpUrl {
        require(path.startsWith("/")) { "API paths must be absolute: $path" }
        return url.newBuilder().encodedPath(path).build()
    }

    override fun equals(other: Any?): Boolean = other is FlareOrigin && other.url == url

    override fun hashCode(): Int = url.hashCode()

    override fun toString(): String = url.toString().trimEnd('/')

    companion object {
        /**
         * Parses what someone typed into an instance field.
         *
         * @throws InvalidOriginException when the text is not an HTTP(S) address.
         */
        fun parse(input: String): FlareOrigin {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) throw InvalidOriginException("Enter your Flare address.")
            if (trimmed.any { it.isWhitespace() }) {
                throw InvalidOriginException("Addresses can't contain spaces.")
            }
            val schemeEnd = trimmed.indexOf("://")
            if (schemeEnd > 0) {
                val scheme = trimmed.substring(0, schemeEnd).lowercase()
                if (scheme != "http" && scheme != "https") {
                    throw InvalidOriginException("Use an http:// or https:// address.")
                }
            }
            val withScheme = if (schemeEnd > 0) trimmed else "https://$trimmed"
            val parsed = withScheme.toHttpUrlOrNull()
                ?: throw InvalidOriginException("That doesn't look like a web address.")
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
                throw InvalidOriginException("Remove the username and password from the address.")
            }
            val origin = HttpUrl.Builder()
                .scheme(parsed.scheme)
                .host(parsed.host)
                .port(parsed.port)
                .build()
            return FlareOrigin(origin)
        }

        /** Returns `null` instead of throwing for text that isn't an address. */
        fun parseOrNull(input: String): FlareOrigin? =
            try {
                parse(input)
            } catch (_: InvalidOriginException) {
                null
            }
    }
}

class InvalidOriginException(message: String) : IllegalArgumentException(message)
