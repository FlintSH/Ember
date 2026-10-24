package dev.fl1nt.ember.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MimeTypesTest {

    private val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)
    private val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)
    private val zip = bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0)

    @Test
    fun `sniffs signatures that Flare's detector checks`() {
        assertThat(MimeTypes.sniff(png)).isEqualTo("image/png")
        assertThat(MimeTypes.sniff(jpeg)).isEqualTo("image/jpeg")
        assertThat(MimeTypes.sniff("GIF89a....".toByteArray())).isEqualTo("image/gif")
        assertThat(MimeTypes.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray())).isEqualTo("image/webp")
        assertThat(MimeTypes.sniff("%PDF-1.7".toByteArray())).isEqualTo("application/pdf")
        assertThat(MimeTypes.sniff(zip)).isEqualTo("application/zip")
        assertThat(MimeTypes.sniff(isoMedia("heic"))).isEqualTo("image/heic")
        assertThat(MimeTypes.sniff(isoMedia("isom"))).isEqualTo("video/mp4")
        assertThat(MimeTypes.sniff(isoMedia("qt  "))).isEqualTo("video/quicktime")
        assertThat(MimeTypes.sniff(isoMedia("M4A "))).isEqualTo("audio/x-m4a")
        assertThat(MimeTypes.sniff("#!AMR\n".toByteArray())).isEqualTo("audio/amr")
    }

    @Test
    fun `leaves text alone`() {
        assertThat(MimeTypes.sniff("hello world".toByteArray())).isNull()
        assertThat(MimeTypes.sniff("{\"a\":1}".toByteArray())).isNull()
        assertThat(MimeTypes.sniff("BMW owners club".toByteArray())).isNull()
        assertThat(MimeTypes.sniff(ByteArray(0))).isNull()
    }

    @Test
    fun `replaces a generic declared type with the detected one`() {
        assertThat(MimeTypes.resolve("application/octet-stream", png, "upload")).isEqualTo("image/png")
        assertThat(MimeTypes.resolve(null, png, "upload")).isEqualTo("image/png")
        assertThat(MimeTypes.resolve("*/*", jpeg, "x")).isEqualTo("image/jpeg")
    }

    @Test
    fun `keeps a declared type the server will accept`() {
        assertThat(MimeTypes.resolve("image/jpg", jpeg, "a.jpg")).isEqualTo("image/jpg")
        assertThat(MimeTypes.resolve("video/quicktime", isoMedia("isom"), "clip.mov")).isEqualTo("video/quicktime")
        assertThat(MimeTypes.resolve("text/markdown", "# Notes".toByteArray(), "notes.md")).isEqualTo("text/markdown")
    }

    @Test
    fun `corrects a declared type that contradicts the bytes`() {
        assertThat(MimeTypes.resolve("image/jpeg", png, "really-a-png.jpg")).isEqualTo("image/png")
    }

    @Test
    fun `prefers a specific zip container type from the extension`() {
        assertThat(MimeTypes.resolve(null, zip, "report.docx"))
            .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        assertThat(MimeTypes.resolve(null, zip, "build.apk")).isEqualTo("application/vnd.android.package-archive")
        assertThat(MimeTypes.resolve(null, zip, "bundle")).isEqualTo("application/zip")
    }

    @Test
    fun `falls back to the extension, then octet-stream, for undetectable content`() {
        assertThat(MimeTypes.resolve(null, "a,b\n1,2".toByteArray(), "data.CSV")).isEqualTo("text/csv")
        assertThat(MimeTypes.resolve(null, "plain".toByteArray(), "no-extension")).isEqualTo(MimeTypes.OCTET_STREAM)
        assertThat(MimeTypes.resolve("text/plain; charset=utf-8", "plain".toByteArray(), "x.bin")).isEqualTo("text/plain")
    }

    @Test
    fun `matches follows Flare's equivalence rules`() {
        assertThat(MimeTypes.matches("application/zip", "application/x-zip-compressed")).isTrue()
        assertThat(MimeTypes.matches("application/zip", "application/epub+zip")).isTrue()
        assertThat(MimeTypes.matches("video/mp4", "audio/x-m4a")).isTrue()
        assertThat(MimeTypes.matches("video/webm", "video/x-matroska")).isTrue()
        assertThat(MimeTypes.matches("image/heif", "image/heic")).isTrue()
        assertThat(MimeTypes.matches("image/png", "image/jpeg")).isFalse()
        assertThat(MimeTypes.matches("audio/ogg", "video/ogg")).isFalse()
    }

    private fun isoMedia(brand: String): ByteArray =
        bytes(0, 0, 0, 0x18) + "ftyp".toByteArray() + brand.toByteArray() + bytes(0, 0, 0, 0)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
