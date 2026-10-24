package dev.fl1nt.ember.api

enum class Visibility { PUBLIC, PRIVATE }

/** Relative expiration. `MONTH` is a calendar month in UTC, not 30 days. */
enum class Expiration { DISABLED, HOUR, DAY, WEEK, MONTH }

enum class ExpiryAction { DELETE, SET_PRIVATE }

/**
 * Chooses an upload profile or destination folder.
 *
 * [Inherit] sends nothing, so Flare applies the account's default profile (or a
 * token's bound profile). [None] explicitly opts out.
 */
sealed interface Selection {
    data object Inherit : Selection

    data object None : Selection

    data class Id(val id: String) : Selection {
        init {
            require(id.isNotBlank() && id.none { it.isWhitespace() || it.isISOControl() }) {
                "IDs can't be blank or contain spaces."
            }
        }
    }

    /** The header value Flare expects, or `null` to omit the header. */
    val headerValue: String?
        get() = when (this) {
            Inherit -> null
            None -> "none"
            is Id -> id
        }
}

/**
 * Per-upload options. Every `null` field is omitted so the selected profile, the
 * account defaults, or a bound token supplies it. Explicit values override them,
 * and a profile-bound token rejects overrides that differ from its profile.
 */
data class UploadOptions(
    val visibility: Visibility? = null,
    val password: String? = null,
    val expiration: Expiration? = null,
    val expiryAction: ExpiryAction? = null,
    val profile: Selection = Selection.Inherit,
    val folder: Selection = Selection.Inherit,
) {
    init {
        if (password != null) {
            require(password.isNotEmpty()) { "Use null instead of an empty password." }
            // Flare reads the multipart value "null" as no password, which would leave the file unprotected.
            require(password != "null") { "Flare can't store the password \"null\". Choose a different password." }
            require(password.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_BYTES) {
                "Flare passwords can be at most $MAX_PASSWORD_BYTES bytes."
            }
        }
    }

    /** Multipart form fields, in the order Flare documents them. */
    internal fun formFields(): List<Pair<String, String>> = buildList {
        visibility?.let { add("visibility" to it.name) }
        password?.let { add("password" to it) }
        expiration?.let { add("expiration" to it.name) }
        expiryAction?.let { add("expiryAction" to it.name) }
    }

    /** Profile and folder must be chosen before any bytes are sent. */
    internal fun selectionHeaders(): List<Pair<String, String>> = buildList {
        profile.headerValue?.let { add("X-Upload-Profile" to it) }
        folder.headerValue?.let { add("X-Upload-Folder" to it) }
    }

    internal fun chunkInit(fileName: String, mimeType: String, size: Long) = ChunkInitRequest(
        filename = fileName,
        mimeType = mimeType,
        size = size,
        visibility = visibility?.name,
        password = password,
        expiration = expiration?.name,
        expiryAction = expiryAction?.name,
    )

    companion object {
        /** Flare rejects file passwords longer than 72 UTF-8 bytes. */
        const val MAX_PASSWORD_BYTES = 72
    }
}
