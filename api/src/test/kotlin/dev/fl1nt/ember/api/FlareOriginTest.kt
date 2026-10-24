package dev.fl1nt.ember.api

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FlareOriginTest {

    @Test
    fun `adds https when the scheme is missing`() {
        assertThat(FlareOrigin.parse("files.example.com").toString()).isEqualTo("https://files.example.com")
    }

    @Test
    fun `keeps only the origin of a pasted dashboard address`() {
        val origin = FlareOrigin.parse("  https://files.example.com/dashboard/upload?folder=abc#top ")
        assertThat(origin.toString()).isEqualTo("https://files.example.com")
        assertThat(origin.resolve("/api/files").toString()).isEqualTo("https://files.example.com/api/files")
    }

    @Test
    fun `keeps explicit ports and plain http for local instances`() {
        val origin = FlareOrigin.parse("http://192.168.1.20:3000/")
        assertThat(origin.toString()).isEqualTo("http://192.168.1.20:3000")
        assertThat(origin.isHttps).isFalse()
        assertThat(origin.displayName).isEqualTo("192.168.1.20:3000")
    }

    @Test
    fun `hides default ports from the display name`() {
        assertThat(FlareOrigin.parse("https://files.example.com:443").displayName).isEqualTo("files.example.com")
        assertThat(FlareOrigin.parse("http://flare.local:80").displayName).isEqualTo("flare.local")
    }

    @Test
    fun `lowercases the host`() {
        assertThat(FlareOrigin.parse("HTTPS://Files.Example.COM").toString()).isEqualTo("https://files.example.com")
    }

    @Test
    fun `rejects other schemes, credentials, spaces and empty input`() {
        listOf("", "   ", "ftp://files.example.com", "javascript:alert(1)", "files example.com", "https://user:pw@files.example.com")
            .forEach { input ->
                assertThrows("Expected $input to be rejected", InvalidOriginException::class.java) {
                    FlareOrigin.parse(input)
                }
            }
    }

    @Test
    fun `equality follows the normalized origin`() {
        assertThat(FlareOrigin.parse("files.example.com/dashboard")).isEqualTo(FlareOrigin.parse("https://files.example.com"))
        assertThat(FlareOrigin.parseOrNull("not a url")).isNull()
    }
}
