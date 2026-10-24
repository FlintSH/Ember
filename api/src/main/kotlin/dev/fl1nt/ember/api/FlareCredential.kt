package dev.fl1nt.ember.api

/**
 * A secret that authenticates requests to Flare.
 *
 * Flare accepts two kinds of bearer credential:
 *
 * - A named API token (`flr_…`), created in **Profile → Integrations**. It only
 *   reaches the routes allowed by its scopes, and can be revoked on its own.
 * - The older account upload token used by generated ShareX, Flameshot, and
 *   Spectacle configurations. It is not limited by scopes, so it can also list
 *   folders, but regenerating it breaks every tool that shares it.
 *
 * [toString] never includes the secret.
 */
sealed class FlareCredential(val secret: String) {

    class NamedToken(secret: String) : FlareCredential(secret)

    class AccountToken(secret: String) : FlareCredential(secret)

    val kind: Kind get() = if (this is NamedToken) Kind.NAMED_TOKEN else Kind.ACCOUNT_TOKEN

    internal val authorizationHeader: String get() = "Bearer $secret"

    override fun equals(other: Any?): Boolean =
        other is FlareCredential && other.kind == kind && other.secret == secret

    override fun hashCode(): Int = 31 * kind.hashCode() + secret.hashCode()

    override fun toString(): String = "FlareCredential(kind=$kind, secret=<redacted>)"

    enum class Kind { NAMED_TOKEN, ACCOUNT_TOKEN }

    companion object {
        const val NAMED_TOKEN_PREFIX = "flr_"

        /** Classifies a pasted secret. Surrounding whitespace is ignored. */
        fun of(secret: String): FlareCredential {
            val trimmed = secret.trim()
            require(trimmed.isNotEmpty()) { "A token is required." }
            require(trimmed.none { it.isWhitespace() || it.isISOControl() }) {
                "Tokens can't contain spaces or line breaks."
            }
            return if (trimmed.startsWith(NAMED_TOKEN_PREFIX)) NamedToken(trimmed) else AccountToken(trimmed)
        }
    }
}
