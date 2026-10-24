package dev.fl1nt.ember.api

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FlareCredentialTest {

    @Test
    fun `classifies named tokens by their prefix`() {
        val credential = FlareCredential.of("  flr_abcDEF123  ")
        assertThat(credential).isInstanceOf(FlareCredential.NamedToken::class.java)
        assertThat(credential.secret).isEqualTo("flr_abcDEF123")
        assertThat(credential.kind).isEqualTo(FlareCredential.Kind.NAMED_TOKEN)
    }

    @Test
    fun `treats anything else as the account upload token`() {
        val credential = FlareCredential.of("3f1c0f0e-legacy")
        assertThat(credential).isInstanceOf(FlareCredential.AccountToken::class.java)
    }

    @Test
    fun `never prints the secret`() {
        val credential = FlareCredential.of("flr_super-secret")
        assertThat(credential.toString()).doesNotContain("super-secret")
        assertThat(credential.toString()).contains("redacted")
    }

    @Test
    fun `rejects blank tokens and tokens with whitespace inside`() {
        assertThrows(IllegalArgumentException::class.java) { FlareCredential.of("   ") }
        assertThrows(IllegalArgumentException::class.java) { FlareCredential.of("flr_a b") }
        assertThrows(IllegalArgumentException::class.java) { FlareCredential.of("flr_a\nb") }
    }
}
