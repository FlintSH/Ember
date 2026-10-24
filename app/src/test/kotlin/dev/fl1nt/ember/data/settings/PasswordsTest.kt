package dev.fl1nt.ember.data.settings

import com.google.common.truth.Truth.assertThat
import dev.fl1nt.ember.R
import org.junit.Test

class PasswordsTest {

    @Test
    fun `accepts passwords Flare keeps, and none at all`() {
        assertThat(passwordProblem("")).isNull()
        assertThat(passwordProblem("correct horse battery staple")).isNull()
        assertThat(passwordProblem("Null")).isNull()
    }

    @Test
    fun `refuses passwords Flare would drop or reject`() {
        // Flare reads "null" as no password, which would leave the file open.
        assertThat(passwordProblem("null")).isEqualTo(R.string.field_password_null)
        // 72 bytes, not characters: each of these is 3 bytes in UTF-8.
        assertThat(passwordProblem("日".repeat(24))).isNull()
        assertThat(passwordProblem("日".repeat(25))).isEqualTo(R.string.field_password_too_long)
    }
}
