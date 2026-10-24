package dev.fl1nt.ember.api

/** Whether a credential can use one area of Flare's API. */
enum class Access {
    GRANTED,

    /** Flare returned 401: the token lacks the scope, or isn't valid at all. */
    NOT_IN_TOKEN,

    /** Flare returned 403: the token is fine, but the account's roles forbid it. */
    DENIED_BY_ROLE,

    /** The check didn't produce a usable answer, for example after a timeout. */
    UNKNOWN,
    ;

    val isGranted: Boolean get() = this == GRANTED
}

/**
 * What a credential can do, discovered without changing anything in Flare.
 *
 * Paste creation can't be checked ahead of time: it needs the `pastes.create`
 * role permission, which Flare only verifies when a paste is created.
 */
data class Capabilities(
    val uploadFiles: Access,
    val readFiles: Access,
    val readLinks: Access,
    val manageLinks: Access,
    val listFolders: Access,
) {
    /** Every check returned 401: the token is unknown, expired, revoked, or has no useful scope. */
    val rejectedEverywhere: Boolean
        get() = listOf(uploadFiles, readFiles, readLinks, manageLinks, listFolders).all { it == Access.NOT_IN_TOKEN }

    companion object {
        val NONE = Capabilities(
            uploadFiles = Access.UNKNOWN,
            readFiles = Access.UNKNOWN,
            readLinks = Access.UNKNOWN,
            manageLinks = Access.UNKNOWN,
            listFolders = Access.UNKNOWN,
        )
    }
}
