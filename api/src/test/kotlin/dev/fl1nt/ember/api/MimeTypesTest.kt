package dev.fl1nt.ember.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Expected types are what file-type 21.3.4, the detector Flare 3.0.0 runs,
 * reports for the same bytes, including its guesses about text: Flare rejects
 * any other claim for those bytes.
 */
class MimeTypesTest {

    private val png = pngWith(chunk("IHDR", ByteArray(13)), chunk("IDAT", ByteArray(4)))
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
        assertThat(MimeTypes.sniff("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray())).isEqualTo("audio/wav")
        assertThat(MimeTypes.sniff("ID3\u0004\u0000\u0000\u0000\u0000\u0001\u0000".toByteArray())).isEqualTo("audio/mpeg")
        assertThat(MimeTypes.sniff(bytes(0xFF, 0xFB, 0x90, 0x00))).isEqualTo("audio/mpeg")
        assertThat(MimeTypes.sniff(bytes(0xFF, 0xF1, 0x50, 0x80))).isEqualTo("audio/aac")
    }

    @Test
    fun `leaves ordinary text alone`() {
        assertThat(MimeTypes.sniff("hello world".toByteArray())).isNull()
        assertThat(MimeTypes.sniff("{\"a\":1}".toByteArray())).isNull()
        assertThat(MimeTypes.sniff("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray())).isNull()
        assertThat(MimeTypes.sniff(ByteArray(0))).isNull()
    }

    @Test
    fun `makes the same guesses about text that Flare's detector makes`() {
        // Flare would reject a text claim for these, so Ember claims what Flare sees.
        assertThat(MimeTypes.resolve("text/plain", "BMW owners club".toByteArray(), "cars.txt")).isEqualTo("image/bmp")
        assertThat(MimeTypes.resolve("text/plain", "GIFs I like".toByteArray(), "list.txt")).isEqualTo("image/gif")
        assertThat(MimeTypes.resolve("text/plain", "MZ notes".toByteArray(), "notes.txt")).isEqualTo("application/x-msdownload")
        // A UTF-16 byte order mark reads as an MPEG audio frame, as Windows "Unicode Text" exports do.
        val unicodeCsv = bytes(0xFF, 0xFE) + "name,city\r\n".toByteArray(Charsets.UTF_16LE)
        assertThat(MimeTypes.resolve("text/csv", unicodeCsv, "export.csv")).isEqualTo("audio/mpeg")
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
        assertThat(MimeTypes.matches("image/heic-sequence", "image/heic")).isFalse()
        assertThat(MimeTypes.matches("image/x-adobe-dng", "image/tiff")).isFalse()
    }

    @Test
    fun `tells raw photos apart the way Flare does`() {
        val dng = tiff(254, 256, 257, 258, 259, 262, 271, 272, 273, 50_706, 50_708)
        assertThat(MimeTypes.sniff(dng)).isEqualTo("image/x-adobe-dng")
        assertThat(MimeTypes.resolve("image/x-adobe-dng", dng, "PXL_0001.dng")).isEqualTo("image/x-adobe-dng")
        assertThat(MimeTypes.resolve(null, dng, "PXL_0001.dng")).isEqualTo("image/x-adobe-dng")
        assertThat(MimeTypes.resolve("image/tiff", dng, "PXL_0001.dng")).isEqualTo("image/x-adobe-dng")
        assertThat(MimeTypes.sniff(tiff(254, 256, 50_706, bigEndian = true))).isEqualTo("image/x-adobe-dng")

        val cr2 = "II*\u0000".toByteArray() + int32(16) + "CR\u0002\u0000".toByteArray() + int32(0) + ifd(listOf(256, 257), bigEndian = false)
        assertThat(MimeTypes.resolve("image/x-canon-cr2", cr2, "IMG_1.CR2")).isEqualTo("image/x-canon-cr2")
        assertThat(MimeTypes.sniff(tiff(254, 256, 50_341))).isEqualTo("image/x-sony-arw")
        // Flare reads 28 tags starting with NewSubfileType as Nikon's layout.
        assertThat(MimeTypes.sniff(tiff(*(listOf(254) + (256..282)).toIntArray(), bigEndian = true))).isEqualTo("image/x-nikon-nef")
        assertThat(MimeTypes.sniff("IIRO\u0008\u0000\u0000\u0000\u0018".toByteArray() + ByteArray(20))).isEqualTo("image/x-olympus-orf")
        assertThat(MimeTypes.sniff(bytes(0x49, 0x49, 0x55, 0, 0x18, 0, 0, 0, 0x88, 0xE7, 0x74, 0xD8) + ByteArray(20)))
            .isEqualTo("image/x-panasonic-rw2")
        assertThat(MimeTypes.sniff("FUJIFILMCCD-RAW 0201".toByteArray() + ByteArray(40))).isEqualTo("image/x-fujifilm-raf")
        assertThat(MimeTypes.resolve(null, isoMedia("crx "), "IMG_2.CR3")).isEqualTo("image/x-canon-cr3")
    }

    @Test
    fun `claims plain TIFF when Flare can only see TIFF`() {
        val plain = tiff(256, 257, 258, 259, 262, 273)
        assertThat(MimeTypes.sniff(plain)).isEqualTo("image/tiff")
        // Flare rejects a raw type its detector can't confirm, so TIFF is the type that uploads.
        assertThat(MimeTypes.resolve("image/x-pentax-pef", plain, "IMGP0001.PEF")).isEqualTo("image/tiff")
    }

    @Test
    fun `keeps the declared type when Flare can't read the TIFF directory`() {
        val farAway = "II*\u0000".toByteArray() + int32(100_000) + ByteArray(64)
        assertThat(MimeTypes.sniff(farAway)).isNull()
        assertThat(MimeTypes.resolve("image/x-samsung-srw", farAway, "a.srw")).isEqualTo("image/x-samsung-srw")
    }

    @Test
    fun `follows Flare's HEIF and AVIF brands, including sequences`() {
        assertThat(MimeTypes.sniff(isoMedia("heix"))).isEqualTo("image/heic")
        assertThat(MimeTypes.sniff(isoMedia("hevc"))).isEqualTo("image/heic-sequence")
        assertThat(MimeTypes.sniff(isoMedia("hevx"))).isEqualTo("image/heic-sequence")
        assertThat(MimeTypes.sniff(isoMedia("mif1"))).isEqualTo("image/heif")
        assertThat(MimeTypes.sniff(isoMedia("msf1"))).isEqualTo("image/heif-sequence")
        assertThat(MimeTypes.sniff(isoMedia("avif"))).isEqualTo("image/avif")
        assertThat(MimeTypes.sniff(isoMedia("avis"))).isEqualTo("image/avif")
        // Flare's detector doesn't know these HEIF brands and reports MP4.
        assertThat(MimeTypes.sniff(isoMedia("heim"))).isEqualTo("video/mp4")
        assertThat(MimeTypes.sniff(isoMedia("HEIC"))).isEqualTo("video/mp4")

        assertThat(MimeTypes.resolve("image/heic", isoMedia("hevc"), "burst.heic")).isEqualTo("image/heic-sequence")
        assertThat(MimeTypes.resolve("image/heif", isoMedia("msf1"), "burst.heif")).isEqualTo("image/heif-sequence")
        assertThat(MimeTypes.resolve("image/heic", isoMedia("mif1"), "photo.heic")).isEqualTo("image/heic")
        assertThat(MimeTypes.resolve("image/avif", isoMedia("avis"), "loop.avif")).isEqualTo("image/avif")
    }

    @Test
    fun `chooses a video type when Flare sees video in an audio file`() {
        assertThat(MimeTypes.sniff(isoMedia("3gp4"))).isEqualTo("video/3gpp")
        assertThat(MimeTypes.sniff(isoMedia("3g2a"))).isEqualTo("video/3gpp2")
        assertThat(MimeTypes.resolve("audio/3gpp", isoMedia("3gp4"), "voice.3gp")).isEqualTo("video/3gpp")
        assertThat(MimeTypes.resolve("audio/mp4", isoMedia("isom"), "voice.m4a")).isEqualTo("audio/mp4")
        assertThat(MimeTypes.resolve("audio/webm", ebml("webm"), "voice.webm")).isEqualTo("video/webm")
        assertThat(MimeTypes.resolve("audio/ogg", ogg(0x80, *"theora".codes()), "clip.ogg")).isEqualTo("video/ogg")
        // QuickTime files without an ftyp box.
        assertThat(MimeTypes.sniff(bytes(0, 0, 0, 8) + "moov".toByteArray() + ByteArray(8))).isEqualTo("video/quicktime")
    }

    @Test
    fun `reads Matroska and Ogg codecs`() {
        assertThat(MimeTypes.sniff(ebml("webm"))).isEqualTo("video/webm")
        assertThat(MimeTypes.sniff(ebml("matroska"))).isEqualTo("video/matroska")
        assertThat(MimeTypes.resolve("video/x-matroska", ebml("matroska"), "a.mkv")).isEqualTo("video/x-matroska")
        assertThat(MimeTypes.sniff(ogg(*"OpusHead".codes()))).isEqualTo("audio/ogg")
        assertThat(MimeTypes.resolve("audio/opus", ogg(*"OpusHead".codes()), "note.opus")).isEqualTo("audio/opus")
        assertThat(MimeTypes.sniff(ogg(0x01, *"vorbis".codes()))).isEqualTo("audio/ogg")
        assertThat(MimeTypes.sniff(ogg(0x7F, *"FLAC".codes()))).isEqualTo("audio/ogg")
        assertThat(MimeTypes.sniff(ogg(0x00, *"fishead".codes()))).isEqualTo("application/ogg")
    }

    @Test
    fun `tells animated PNG from PNG`() {
        val apng = pngWith(chunk("IHDR", ByteArray(13)), chunk("acTL", ByteArray(8)), chunk("IDAT", ByteArray(4)))
        assertThat(MimeTypes.sniff(apng)).isEqualTo("image/apng")
        assertThat(MimeTypes.resolve("image/png", apng, "sticker.png")).isEqualTo("image/apng")
        val lateAnimation = pngWith(chunk("IHDR", ByteArray(13)), chunk("IDAT", ByteArray(4)), chunk("acTL", ByteArray(8)))
        assertThat(MimeTypes.sniff(lateAnimation)).isEqualTo("image/png")
    }

    @Test
    fun `reads ZIP entries like Flare`() {
        val docx = zipOf(
            "[Content_Types].xml" to contentTypes("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"),
            "word/document.xml" to "<w/>".toByteArray(),
        )
        assertThat(MimeTypes.sniff(docx)).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        assertThat(MimeTypes.resolve(MimeTypes.OCTET_STREAM, docx, "notes")).isEqualTo(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
        val xlsx = zipOf("[Content_Types].xml" to contentTypes("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"))
        assertThat(MimeTypes.sniff(xlsx)).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        val pptx = zipOf("[Content_Types].xml" to contentTypes("application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"))
        assertThat(MimeTypes.sniff(pptx)).isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation")
        // Macro-enabled documents aren't in Flare's ZIP family, so only the detected name uploads.
        val docm = zipOf("[Content_Types].xml" to contentTypes("application/vnd.ms-word.document.macroEnabled.main+xml"))
        assertThat(MimeTypes.resolve("application/zip", docm, "macro.docm")).isEqualTo("application/vnd.ms-word.document.macroenabled.12")

        val apk = zipOf("AndroidManifest.xml" to ByteArray(300) { 7 }, "classes.dex" to "dex\n035".toByteArray())
        assertThat(MimeTypes.sniff(apk)).isEqualTo("application/vnd.android.package-archive")
        // An APK that lists its v1 signature manifest first looks like a JAR to Flare.
        val signedFirst = zipOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\n".toByteArray(), "classes.dex" to ByteArray(4))
        assertThat(MimeTypes.resolve("application/vnd.android.package-archive", signedFirst, "app.apk")).isEqualTo("application/java-archive")

        val epub = zipOf("mimetype" to "application/epub+zip".toByteArray(), "META-INF/container.xml" to "<c/>".toByteArray(), stored = true)
        assertThat(MimeTypes.sniff(epub)).isEqualTo("application/epub+zip")
        val odt = zipOf("mimetype" to "application/vnd.oasis.opendocument.text".toByteArray(), stored = true)
        assertThat(MimeTypes.sniff(odt)).isEqualTo("application/vnd.oasis.opendocument.text")
        assertThat(MimeTypes.sniff(zipOf("notes.txt" to "hello".toByteArray()))).isEqualTo("application/zip")
    }

    @Test
    fun `follows Flare's text signatures`() {
        val gpx = "<?xml version=\"1.0\"?><gpx/>".toByteArray()
        assertThat(MimeTypes.sniff(gpx)).isEqualTo("application/xml")
        assertThat(MimeTypes.resolve("application/gpx+xml", gpx, "ride.gpx")).isEqualTo("application/xml")
        val svg = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()
        assertThat(MimeTypes.resolve("image/svg+xml", svg, "logo.svg")).isEqualTo("image/svg+xml")
        assertThat(MimeTypes.sniff(bytes(0xEF, 0xBB, 0xBF) + gpx)).isEqualTo("application/xml")
        assertThat(MimeTypes.sniff(bytes(0xFF, 0xFE) + "<?xml ".toByteArray(Charsets.UTF_16LE))).isEqualTo("application/xml")

        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Alex\r\nEND:VCARD\r\n".toByteArray()
        assertThat(MimeTypes.resolve("text/x-vcard", card, "Alex.vcf")).isEqualTo("text/vcard")
        assertThat(MimeTypes.sniff("BEGIN:VCALENDAR\r\n".toByteArray())).isEqualTo("text/calendar")
        assertThat(MimeTypes.sniff("WEBVTT\n\n00:00.000 --> 00:01.000\n".toByteArray())).isEqualTo("text/vtt")
        assertThat(MimeTypes.sniff("{\\rtf1\\ansi hi}".toByteArray())).isEqualTo("application/rtf")
    }

    @Test
    fun `recognizes legacy Office files and executables`() {
        val doc = bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) + ByteArray(504)
        assertThat(MimeTypes.resolve("application/msword", doc, "old.doc")).isEqualTo("application/x-cfb")
        val exe = ByteArray(256).also {
            it[0] = 'M'.code.toByte()
            it[1] = 'Z'.code.toByte()
            it[0x3C] = 0x80.toByte()
            "PE\u0000\u0000".toByteArray().copyInto(it, 0x80)
        }
        assertThat(MimeTypes.sniff(exe)).isEqualTo("application/x-msdownload")
    }

    @Test
    fun `maps raw and text extensions to Flare's spellings`() {
        assertThat(MimeTypes.fromExtension("a.CR2")).isEqualTo("image/x-canon-cr2")
        assertThat(MimeTypes.fromExtension("a.raf")).isEqualTo("image/x-fujifilm-raf")
        assertThat(MimeTypes.fromExtension("a.vcf")).isEqualTo("text/vcard")
        assertThat(MimeTypes.fromExtension("a.flac")).isEqualTo("audio/flac")
    }

    private fun isoMedia(brand: String): ByteArray =
        bytes(0, 0, 0, 0x18) + "ftyp".toByteArray() + brand.toByteArray() + bytes(0, 0, 0, 0)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun String.codes(): IntArray = map { it.code }.toIntArray()

    private fun int32(value: Int, bigEndian: Boolean = false): ByteArray =
        ByteBuffer.allocate(4).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    /** A directory of SHORT entries with the given tags, then the next-directory offset. */
    private fun ifd(tags: List<Int>, bigEndian: Boolean): ByteArray {
        val buffer = ByteBuffer.allocate(2 + tags.size * 12 + 4).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(tags.size.toShort())
        tags.forEach { buffer.putShort(it.toShort()).putShort(3).putInt(1).putInt(0) }
        return buffer.putInt(0).array()
    }

    private fun tiff(vararg tags: Int, bigEndian: Boolean = false): ByteArray {
        val order = if (bigEndian) "MM" else "II"
        val head = ByteBuffer.allocate(8).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            .put(order.toByteArray()).putShort(42).putInt(8).array()
        return head + ifd(tags.toList(), bigEndian)
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val crc = CRC32().apply { update(type.toByteArray() + data) }.value.toInt()
        return int32(data.size, bigEndian = true) + type.toByteArray() + data + int32(crc, bigEndian = true)
    }

    private fun pngWith(vararg chunks: ByteArray): ByteArray =
        chunks.fold(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) { acc, next -> acc + next }

    /** An Ogg page whose first packet starts with [codec] at byte 28. */
    private fun ogg(vararg codec: Int): ByteArray = "OggS".toByteArray() + ByteArray(24) + bytes(*codec) + ByteArray(16)

    /** An EBML header whose DocType is [docType]. */
    private fun ebml(docType: String): ByteArray {
        fun element(id: ByteArray, payload: ByteArray) = id + bytes(0x80 or payload.size) + payload
        val body = element(bytes(0x42, 0x86), bytes(1)) + element(bytes(0x42, 0x82), docType.toByteArray()) +
            element(bytes(0x42, 0x87), bytes(4))
        return element(bytes(0x1A, 0x45, 0xDF, 0xA3), body) + bytes(0x18, 0x53, 0x80, 0x67, 0x01) + ByteArray(16)
    }

    private fun contentTypes(main: String) = (
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/main.xml\" ContentType=\"$main\"/></Types>"
        ).toByteArray()

    /** Java's ZipOutputStream writes deflated entries with data descriptors, as many Android apps do. */
    private fun zipOf(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray().copyOf(minOf(out.size(), MimeTypes.HEADER_BYTES))
    }
}
