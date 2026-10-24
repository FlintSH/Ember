package dev.fl1nt.ember.api

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class UploadOptionsTest {

    @Test
    fun `inherits everything by default`() {
        val options = UploadOptions()
        assertThat(options.formFields()).isEmpty()
        assertThat(options.selectionHeaders()).isEmpty()
    }

    @Test
    fun `encodes explicit choices as Flare form fields and headers`() {
        val options = UploadOptions(
            visibility = Visibility.PRIVATE,
            password = "hunter2",
            expiration = Expiration.WEEK,
            expiryAction = ExpiryAction.SET_PRIVATE,
            profile = Selection.Id("prof_1"),
            folder = Selection.None,
        )
        assertThat(options.formFields()).containsExactly(
            "visibility" to "PRIVATE",
            "password" to "hunter2",
            "expiration" to "WEEK",
            "expiryAction" to "SET_PRIVATE",
        ).inOrder()
        assertThat(options.selectionHeaders()).containsExactly(
            "X-Upload-Profile" to "prof_1",
            "X-Upload-Folder" to "none",
        ).inOrder()
    }

    @Test
    fun `enforces Flare's 72 byte password limit in UTF-8`() {
        UploadOptions(password = "a".repeat(72))
        assertThrows(IllegalArgumentException::class.java) { UploadOptions(password = "a".repeat(73)) }
        // 24 three-byte characters are 72 bytes; one more is over the limit.
        UploadOptions(password = "€".repeat(24))
        assertThrows(IllegalArgumentException::class.java) { UploadOptions(password = "€".repeat(25)) }
        assertThrows(IllegalArgumentException::class.java) { UploadOptions(password = "") }
    }

    @Test
    fun `rejects malformed IDs`() {
        assertThrows(IllegalArgumentException::class.java) { Selection.Id(" ") }
        assertThrows(IllegalArgumentException::class.java) { Selection.Id("a b") }
    }
}
