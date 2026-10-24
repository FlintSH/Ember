package dev.fl1nt.ember.data.settings

import androidx.annotation.StringRes
import dev.fl1nt.ember.R
import dev.fl1nt.ember.api.UploadOptions

/** Why Flare can't use [password] for a file, or null when it can. An empty password means none. */
@StringRes
fun passwordProblem(password: String): Int? = when {
    password.toByteArray(Charsets.UTF_8).size > UploadOptions.MAX_PASSWORD_BYTES -> R.string.field_password_too_long
    // Flare reads an upload field of exactly "null" as no password, which would leave the file open.
    password == "null" -> R.string.field_password_null
    else -> null
}
