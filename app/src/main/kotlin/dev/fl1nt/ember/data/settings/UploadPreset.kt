package dev.fl1nt.ember.data.settings

import dev.fl1nt.ember.api.Expiration
import dev.fl1nt.ember.api.ExpiryAction
import dev.fl1nt.ember.api.Selection
import dev.fl1nt.ember.api.UploadOptions
import dev.fl1nt.ember.api.Visibility
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Upload choices Ember remembers: app-wide defaults, a sync folder's options, or
 * one share. A `null` field or [Choice.Inherit] means "decide elsewhere": a sync
 * folder falls back to Ember's defaults, and Ember's defaults fall back to the
 * Flare account, its default profile, or a token's bound profile.
 */
@Serializable
data class UploadPreset(
    val visibility: Visibility? = null,
    val expiration: Expiration? = null,
    val expiryAction: ExpiryAction? = null,
    val profile: Choice = Choice.Inherit,
    val folder: Choice = Choice.Inherit,
) {
    /** Fills every inherited field from [fallback]. */
    fun over(fallback: UploadPreset) = UploadPreset(
        visibility = visibility ?: fallback.visibility,
        expiration = expiration ?: fallback.expiration,
        expiryAction = expiryAction ?: fallback.expiryAction,
        profile = if (profile == Choice.Inherit) fallback.profile else profile,
        folder = if (folder == Choice.Inherit) fallback.folder else folder,
    )

    fun toOptions(password: String? = null) = UploadOptions(
        visibility = visibility,
        password = password?.takeIf { it.isNotEmpty() },
        expiration = expiration,
        // Flare ignores the action when nothing expires; don't send a meaningless override.
        expiryAction = expiryAction?.takeIf { expiration != Expiration.DISABLED },
        profile = profile.toSelection(),
        folder = folder.toSelection(),
    )

    fun encode(): String = json.encodeToString(serializer(), this)

    @Serializable
    sealed interface Choice {
        @Serializable
        @SerialName("inherit")
        data object Inherit : Choice

        @Serializable
        @SerialName("none")
        data object None : Choice

        /** A Flare ID, with the name Ember showed when it was chosen. */
        @Serializable
        @SerialName("id")
        data class Id(val id: String, val label: String? = null) : Choice

        fun toSelection(): Selection = when (this) {
            Inherit -> Selection.Inherit
            None -> Selection.None
            is Id -> Selection.Id(id)
        }
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun decode(text: String?): UploadPreset =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: UploadPreset()
    }
}
