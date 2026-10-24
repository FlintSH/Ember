package dev.fl1nt.ember.api.internal

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * The part of file-type 21.3.4 (`core.js`) that recognizes the files people
 * upload from phones.
 *
 * Flare runs file-type on the first 4100 bytes of every upload and rejects a
 * claimed type that doesn't match, so this reads the same window and must reach
 * the same answer. Checks run in file-type's order, because one file can carry
 * several signatures. Containers get the same treatment: TIFF tags tell raw
 * photos apart, ISO media brands tell HEIF stills from sequences, and ZIP entries
 * tell documents from APKs.
 *
 * Text is the exception. file-type also reads short prefixes such as `BM` or
 * `GIF` at the start of ordinary text as images. Ember doesn't repeat those
 * guesses, so text keeps its declared type.
 */
internal object FileTypeSniffer {
    fun detect(header: ByteArray): String? {
        // file-type ignores inputs of fewer than two bytes.
        if (header.size < 2) return null
        val bytes = Window(header, 0)
        val found = bytes.confident(depth = 0)
        return when {
            found == null -> bytes.imprecise()
            found == UNKNOWN -> null
            else -> found
        }
    }

    /** file-type stopped without naming a type. */
    private const val UNKNOWN = ""

    private const val APK = "application/vnd.android.package-archive"
    private const val CONTENT_TYPES = "[Content_Types].xml"
    private const val LOCAL_FILE_HEADER = 0x04034B50L
    private const val DATA_DESCRIPTOR = 0x08074B50L

    // file-type's limits for reading ZIP entries and nested headers.
    private const val MAX_ZIP_ENTRY_BYTES = 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 1024
    private const val MAX_BOMS = 256

    private val DEX = Regex("classes\\d*\\.dex")
    private val WEBVTT_SEPARATORS = setOf('\n'.code, '\r'.code, '\t'.code, ' '.code, 0)

    /** The first [size] bytes from [start]. Reads past the end see 0, like file-type's zero-filled buffer. */
    private class Window(private val data: ByteArray, private val start: Int) {
        val size = data.size - start

        operator fun get(index: Int): Int = if (index in 0 until size) data[start + index].toInt() and 0xFF else 0

        fun at(offset: Int, vararg values: Int): Boolean = values.indices.all { this[offset + it] == values[it] }

        fun ascii(offset: Int, text: String): Boolean = text.indices.all { this[offset + it] == text[it].code }

        fun utf16(offset: Int, text: String, bigEndian: Boolean): Boolean = text.indices.all {
            val code = text[it].code
            val first = this[offset + it * 2]
            val second = this[offset + it * 2 + 1]
            if (bigEndian) first == code shr 8 && second == code and 0xFF else first == code and 0xFF && second == code shr 8
        }

        fun u16(offset: Int, bigEndian: Boolean): Int =
            if (bigEndian) this[offset] shl 8 or this[offset + 1] else this[offset + 1] shl 8 or this[offset]

        fun u32(offset: Int, bigEndian: Boolean): Long = if (bigEndian) {
            u16(offset, true).toLong() shl 16 or u16(offset + 2, true).toLong()
        } else {
            u16(offset + 2, false).toLong() shl 16 or u16(offset, false).toLong()
        }

        fun latin1(offset: Int, length: Int): String = String(CharArray(length) { this[offset + it].toChar() })

        fun copy(offset: Int, length: Int): ByteArray = ByteArray(length) { this[offset + it].toByte() }

        /** `detectConfident`. `null` means no signature matched, so the imprecise checks run. */
        fun confident(depth: Int): String? {
            // -- 2-byte signatures --
            if (ascii(0, "BM")) return if (at(6, 0, 0, 0, 0)) "image/bmp" else UNKNOWN
            if (ascii(0, "MZ")) return if (portableExecutable()) "application/x-msdownload" else UNKNOWN
            if (ascii(0, "%!")) return if (ascii(2, "PS-Adobe-") && ascii(14, " EPSF-")) "application/eps" else "application/postscript"

            // -- 3-byte signatures --
            if (at(0, 0xEF, 0xBB, 0xBF)) {
                // file-type strips a UTF-8 byte order mark and looks again.
                if (depth >= MAX_BOMS) return UNKNOWN
                return Window(data, start + 3).confident(depth + 1) ?: UNKNOWN
            }
            if (ascii(0, "GIF")) return if (ascii(3, "87a") || ascii(3, "89a")) "image/gif" else UNKNOWN
            if (at(0, 0x1F, 0x8B, 0x08)) return "application/gzip"
            if (ascii(0, "BZh")) return if (this[3] in '1'.code..'9'.code) "application/x-bzip2" else UNKNOWN
            // file-type skips the tag and looks at the audio after it. Flare accepts any
            // audio type for audio, so the frames' exact type doesn't matter.
            if (ascii(0, "ID3")) return "audio/mpeg"

            // -- 4-byte signatures --
            if (at(0, 0xFF, 0xD8, 0xFF)) return if (this[3] == 0xF7) "image/jls" else "image/jpeg"
            if (ascii(0, "8BPS")) return "image/vnd.adobe.photoshop"
            if (at(0, 0x50, 0x4B, 0x03, 0x04)) return zip()
            if (ascii(0, "OggS")) return ogg()
            if (at(0, 0x50, 0x4B) && this[2] in setOf(3, 5, 7) && this[3] in setOf(4, 6, 8)) return "application/zip"
            if (ascii(0, "MThd")) return "audio/midi"
            if (ascii(0, "wOFF") && fontFlavor()) return "font/woff"
            if (ascii(0, "wOF2") && fontFlavor()) return "font/woff2"
            if (ascii(0, "fLaC")) return "audio/flac"
            if (ascii(0, "%PDF")) return "application/pdf"
            if (at(0, 0x00, 0x61, 0x73, 0x6D)) return "application/wasm"
            if (ascii(0, "II")) tiff(bigEndian = false)?.let { return it }
            if (ascii(0, "MM")) tiff(bigEndian = true)?.let { return it }
            if (at(0, 0x1A, 0x45, 0xDF, 0xA3)) return ebml()
            if (ascii(0, "SQLi")) return if (ascii(0, "SQLite format 3")) "application/x-sqlite3" else UNKNOWN
            if (at(0, 0x7F, 0x45, 0x4C, 0x46)) return "application/x-elf"

            // -- 5-byte signatures --
            if (ascii(0, "#!AMR")) return "audio/amr"
            if (ascii(0, "{\\rtf")) return "application/rtf"
            // MPEG program streams; file-type names them video/MP1S and video/MP2P.
            if (at(0, 0x00, 0x00, 0x01, 0xBA) && (this[4] and 0xF1 == 0x21 || this[4] and 0xC4 == 0x44)) return "video/mpeg"

            // -- 6-byte signatures --
            if (at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)) return "application/x-xz"
            if (ascii(0, "<?xml ")) return "application/xml"
            if (at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return "application/x-7z-compressed"
            if (at(0, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) && this[6] in 0..1) return "application/x-rar-compressed"

            // -- 7-byte signatures --
            if (ascii(0, "WEBVTT") && this[6] in WEBVTT_SEPARATORS) return "text/vtt"

            // -- 8-byte signatures --
            if (at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return png()
            if (ascii(4, "free") || ascii(4, "mdat") || ascii(4, "moov") || ascii(4, "wide")) return "video/quicktime"

            // -- 9-byte signatures --
            if (at(0, 0x49, 0x49, 0x52, 0x4F, 0x08, 0x00, 0x00, 0x00, 0x18)) return "image/x-olympus-orf"

            // ISO base media: MP4, QuickTime, 3GP, HEIF, AVIF and Canon CR3.
            if (ascii(4, "ftyp") && this[8] and 0x60 != 0) return isoBrand()

            // -- 12-byte signatures --
            if (ascii(0, "RIFF")) {
                when {
                    ascii(8, "WEBP") -> return "image/webp"
                    ascii(8, "AVI") -> return "video/vnd.avi"
                    ascii(8, "WAVE") -> return "audio/wav"
                    ascii(8, "QLCM") -> return "audio/qcelp"
                }
            }
            if (at(0, 0x49, 0x49, 0x55, 0x00, 0x18, 0x00, 0x00, 0x00, 0x88, 0xE7, 0x74, 0xD8)) return "image/x-panasonic-rw2"
            if (at(0, 0xFF, 0x0A) || at(0, 0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A)) {
                return "image/jxl"
            }
            // Other UTF-16 text skips the remaining signatures, but not the imprecise checks.
            if (at(0, 0xFE, 0xFF)) return if (utf16(2, "<?xml ", bigEndian = true)) "application/xml" else null
            // Compound files: Office 97-2003 documents and Outlook messages.
            if (at(0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) return "application/x-cfb"

            // -- 15-byte and longer signatures --
            if (ascii(0, "BEGIN:")) {
                if (ascii(6, "VCARD")) return "text/vcard"
                if (ascii(6, "VCALENDAR")) return "text/calendar"
            }
            if (ascii(0, "FUJIFILMCCD-RAW")) return "image/x-fujifilm-raf"
            // MPEG transport streams, plain or with Blu-ray's 4-byte prefix. file-type
            // checks two sync bytes; Ember wants a third so text starting with G stays text.
            if (at(0, 0x47) && at(188, 0x47)) return if (at(376, 0x47)) "video/mp2t" else UNKNOWN
            if (at(4, 0x47) && at(196, 0x47)) return if (at(388, 0x47)) "video/mp2t" else UNKNOWN
            if (ascii(257, "ustar") && (this[262] == 0 || this[262] == ' '.code)) return "application/x-tar"
            if (at(0, 0xFF, 0xFE)) {
                if (utf16(2, "<?xml ", bigEndian = false)) return "application/xml"
                // file-type then reads this byte order mark as an MPEG audio frame.
                // Ember keeps the declared type of UTF-16 text instead.
                return UNKNOWN
            }
            if (ascii(0, "-----BEGIN PGP MESSAGE-----")) return "application/pgp-encrypted"
            return null
        }

        /** A Windows executable's `PE` header, where the DOS header points. */
        private fun portableExecutable(): Boolean {
            val offset = u32(0x3C, bigEndian = false)
            return offset + 4 <= size && at(offset.toInt(), 0x50, 0x45, 0x00, 0x00)
        }

        private fun fontFlavor(): Boolean = at(4, 0x00, 0x01, 0x00, 0x00) || ascii(4, "OTTO")

        /** `detectImprecise`, with Flare's default MPEG offset tolerance of 0. */
        fun imprecise(): String? {
            if (at(0, 0x00, 0x00, 0x01, 0xBA) || at(0, 0x00, 0x00, 0x01, 0xB3)) return "video/mpeg"
            if (at(0, 0x00, 0x01, 0x00, 0x00, 0x00)) return "font/ttf"
            if (at(0, 0x00, 0x00, 0x01, 0x00) || at(0, 0x00, 0x00, 0x02, 0x00)) return "image/x-icon"
            if (this[0] == 0xFF && this[1] and 0xE0 == 0xE0) {
                if (this[1] and 0x16 == 0x10) return "audio/aac"
                if (this[1] and 0x06 != 0) return "audio/mpeg"
            }
            return null
        }

        /**
         * Canon CR2 and Nikon NEF are recognized from the header, Sony ARW and DNG
         * from a tag in the first directory. Other TIFF-based raw formats stay
         * `image/tiff`, the only type Flare accepts for them. `null` means it isn't
         * TIFF and the remaining checks run.
         */
        private fun tiff(bigEndian: Boolean): String? {
            val version = u16(2, bigEndian)
            if (version == 43) return "image/tiff"
            if (version != 42) return null
            val directory = u32(4, bigEndian)
            if (directory >= 6) {
                if (ascii(8, "CR")) return "image/x-canon-cr2"
                if (directory >= 8) {
                    val first = u16(8, bigEndian)
                    val second = u16(10, bigEndian)
                    if (first == 0x1C && second == 0xFE || first == 0x1F && second == 0x0B) return "image/x-nikon-nef"
                }
            }
            // file-type gives up when the first directory lies beyond what Flare reads.
            if (directory > size) return UNKNOWN
            var position = directory.toInt()
            if (position + 2 > size) return UNKNOWN
            val tags = u16(position, bigEndian)
            position += 2
            if (tags > 512) return "image/tiff"
            repeat(tags) {
                if (position + 2 > size) return UNKNOWN
                when (u16(position, bigEndian)) {
                    50_341 -> return "image/x-sony-arw"
                    50_706 -> return "image/x-adobe-dng"
                }
                position += 12
            }
            return "image/tiff"
        }

        private fun isoBrand(): String {
            // Major brands are case-sensitive. file-type turns the first NUL into a space and trims.
            val brand = latin1(8, 4).replaceFirst('\u0000', ' ').trim { it.code in JS_SPACE }
            return when (brand) {
                "avif", "avis" -> "image/avif"
                "mif1" -> "image/heif"
                "msf1" -> "image/heif-sequence"
                "heic", "heix" -> "image/heic"
                "hevc", "hevx" -> "image/heic-sequence"
                "qt" -> "video/quicktime"
                "M4V", "M4VH", "M4VP" -> "video/x-m4v"
                "M4P", "F4V", "F4P" -> "video/mp4"
                "M4B", "F4A", "F4B" -> "audio/mp4"
                "M4A" -> "audio/x-m4a"
                "crx" -> "image/x-canon-cr3"
                else -> when {
                    brand.startsWith("3g2") -> "video/3gpp2"
                    brand.startsWith("3g") -> "video/3gpp"
                    // Every other brand, including the HEIF brands heim and heis.
                    else -> "video/mp4"
                }
            }
        }

        private fun ogg(): String {
            // file-type reads the codec identifier at byte 28 and gives up on shorter files.
            if (size < 36) return UNKNOWN
            return when {
                // file-type reports Opus as `audio/ogg; codecs=opus`.
                ascii(28, "OpusHead") -> "audio/ogg"
                at(28, 0x80) && ascii(29, "theora") -> "video/ogg"
                at(28, 0x01) && ascii(29, "video") && at(34, 0x00) -> "video/ogg"
                at(28, 0x7F) && ascii(29, "FLAC") -> "audio/ogg"
                ascii(28, "Speex  ") -> "audio/ogg"
                at(28, 0x01) && ascii(29, "vorbis") -> "audio/ogg"
                else -> "application/ogg"
            }
        }

        /** Walks the PNG chunks: an animation control chunk before the image data means APNG. */
        private fun png(): String {
            var position = 8
            var chunks = 0
            var sawHeader = false
            do {
                if (++chunks > 512) break
                // file-type reads the first chunk header even when the file is too short for it.
                if (position + 8 > size) return UNKNOWN
                val length = u32(position, bigEndian = true)
                val type = latin1(position + 4, 4)
                position += 8
                // A length with the top bit set is negative to file-type.
                if (length > Int.MAX_VALUE) return UNKNOWN
                if (type == "IHDR") {
                    if (length != 13L) return UNKNOWN
                    sawHeader = true
                }
                if (type == "IDAT") return "image/png"
                if (type == "acTL") return "image/apng"
                if (!sawHeader && type != "CgBI") return UNKNOWN
                // file-type settles on PNG when a chunk runs past the bytes it has.
                if (length + 4 > size) return "image/png"
                position = minOf(size.toLong(), position + length + 4).toInt()
            } while (position + 8 < size)
            return "image/png"
        }

        /**
         * EBML's DocType tells WebM from Matroska. file-type counts the header's
         * byte length down one element at a time; this repeats that.
         */
        private fun ebml(): String {
            var position = 0

            fun field(): ByteArray? {
                if (position >= size) return null
                val first = this[position]
                var mask = 0x80
                var extra = 0
                while (first and mask == 0 && mask != 0) {
                    extra++
                    mask = mask shr 1
                }
                if (position + extra + 1 > size) return null
                return copy(position, extra + 1).also { position += it.size }
            }

            // file-type reads at most six bytes of a number, and an ID longer than that as unknown.
            fun unsigned(bytes: ByteArray, from: Int): Long? {
                if (bytes.size - from !in 1..6) return null
                return (from until bytes.size).fold(0L) { value, i -> value shl 8 or (bytes[i].toLong() and 0xFF) }
            }

            fun element(): Pair<Long?, Long>? {
                val id = field() ?: return null
                val length = field() ?: return null
                length[0] = (length[0].toInt() xor (0x80 shr (length.size - 1))).toByte()
                return unsigned(id, 0) to (unsigned(length, maxOf(0, length.size - 6)) ?: 0L)
            }

            val root = element() ?: return UNKNOWN
            var children = root.second
            var count = 0
            while (children > 0) {
                if (++count > 256) return UNKNOWN
                val previous = position
                val (id, length) = element() ?: return UNKNOWN
                if (id == 0x4282L) {
                    if (length > 64 || position + length > size) return UNKNOWN
                    return when (copy(position, length.toInt()).decodeToString().substringBefore('\u0000')) {
                        "webm" -> "video/webm"
                        "matroska" -> "video/matroska"
                        else -> UNKNOWN
                    }
                }
                if (length > size) return UNKNOWN
                position = minOf(size.toLong(), position + length).toInt()
                children--
                if (position <= previous) return UNKNOWN
            }
            return UNKNOWN
        }

        /**
         * Reads local file headers in order, as file-type does, until one decides
         * the type. The central directory, anything unreadable, or running out of
         * bytes ends the walk and leaves plain `application/zip`.
         */
        private fun zip(): String {
            var found: String? = null
            var position = 0
            var entries = 0
            var stop = false
            while (!stop) {
                if (position + 4 > size || u32(position, false) != LOCAL_FILE_HEADER) break
                if (position + 30 > size) break
                val flags = u16(position + 6, false)
                val method = u16(position + 8, false)
                val compressed = u32(position + 18, false)
                val uncompressed = u32(position + 22, false)
                val nameLength = u16(position + 26, false)
                val extraLength = u16(position + 28, false)
                position += 30
                if (position + nameLength > size) break
                val name = copy(position, nameLength).decodeToString()
                position += nameLength
                if (++entries > MAX_ZIP_ENTRIES) break

                var reader: ((ByteArray) -> Unit)? = null
                val readable = compressed <= MAX_ZIP_ENTRY_BYTES && uncompressed <= MAX_ZIP_ENTRY_BYTES
                when {
                    name == "META-INF/mozilla.rsa" -> {
                        found = "application/x-xpinstall"
                        stop = true
                    }
                    name == "META-INF/MANIFEST.MF" -> {
                        found = "application/java-archive"
                        stop = true
                    }
                    name == "mimetype" -> if (readable) {
                        reader = { found = fromMimeType(it.decodeToString().trim { char -> char.code in JS_SPACE }) }
                        stop = true
                    }
                    name == CONTENT_TYPES -> if (readable) {
                        reader = { xml -> mainContentType(xml.decodeToString())?.let { found = fromMimeType(it) } }
                        stop = true
                    }
                    DEX.containsMatchIn(name) -> {
                        found = APK
                        stop = true
                    }
                }

                position = minOf(size, position + extraLength)
                val hasDescriptor = flags and 0x08 != 0
                val data: ByteArray?
                if (hasDescriptor && compressed == 0L) {
                    // The size follows the data, so find the descriptor that records it.
                    val length = descriptorOffset(position).takeIf { it >= 0 } ?: (size - position)
                    data = if (reader != null) copy(position, length) else null
                    position += length
                } else if (reader == null) {
                    if (compressed > size) break
                    position = minOf(size.toLong(), position + compressed).toInt()
                    data = null
                } else {
                    if (compressed > size - position) break
                    data = copy(position, compressed.toInt())
                    position += compressed.toInt()
                }
                if (reader != null) reader(inflate(method, data!!) ?: break)
                if (hasDescriptor) {
                    if (position + 16 > size || u32(position, false) != DATA_DESCRIPTOR) break
                    position += 16
                }
            }
            return found ?: "application/zip"
        }

        private fun descriptorOffset(from: Int): Int {
            for (offset in 0..size - from - 16) {
                if (u32(from + offset, false) == DATA_DESCRIPTOR && u32(from + offset + 8, false) == offset.toLong()) return offset
            }
            return -1
        }
    }

    /** JavaScript's `trim()` whitespace within Latin-1. */
    private val JS_SPACE = setOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0xFEFF)

    private fun inflate(method: Int, data: ByteArray): ByteArray? = when (method) {
        0 -> data
        8 -> inflateRaw(data)
        else -> null
    }

    /** Raw deflate, limited like file-type to 1 MiB. A truncated entry fails, as it does there. */
    private fun inflateRaw(data: ByteArray): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data)
            var output = ByteArray(16 * 1024)
            var total = 0
            var stalls = 0
            while (!inflater.finished()) {
                if (total == output.size) output = output.copyOf(minOf(output.size * 2, MAX_ZIP_ENTRY_BYTES + 1))
                val count = inflater.inflate(output, total, output.size - total)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary() || ++stalls > 8)) return null
                total += count
                if (total > MAX_ZIP_ENTRY_BYTES) return null
            }
            output.copyOf(total)
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    /** The `ContentType` ending in `.main+xml"` in `[Content_Types].xml`, as file-type finds it. */
    private fun mainContentType(xml: String): String? {
        val end = xml.indexOf(".main+xml\"")
        if (end == -1) {
            val model = "application/vnd.ms-package.3dmanufacturing-3dmodel+xml"
            return model.takeIf { xml.contains("ContentType=\"$model\"") }
        }
        val prefix = xml.substring(0, end)
        return prefix.substring(prefix.lastIndexOf('"') + 1)
    }

    /** file-type's names for the types a ZIP's `mimetype` or main content type declares. */
    private fun fromMimeType(declared: String): String? = when (val type = declared.lowercase()) {
        "application/epub+zip",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.text-template",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.spreadsheet-template",
        "application/vnd.oasis.opendocument.presentation",
        "application/vnd.oasis.opendocument.presentation-template",
        "application/vnd.oasis.opendocument.graphics",
        "application/vnd.oasis.opendocument.graphics-template",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "application/vnd.openxmlformats-officedocument.presentationml.template",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        -> type
        "application/vnd.ms-excel.sheet.macroenabled" -> "application/vnd.ms-excel.sheet.macroenabled.12"
        "application/vnd.ms-excel.template.macroenabled" -> "application/vnd.ms-excel.template.macroenabled.12"
        "application/vnd.ms-powerpoint.slideshow.macroenabled" -> "application/vnd.ms-powerpoint.slideshow.macroenabled.12"
        "application/vnd.ms-word.document.macroenabled" -> "application/vnd.ms-word.document.macroenabled.12"
        "application/vnd.ms-word.template.macroenabledtemplate" -> "application/vnd.ms-word.template.macroenabled.12"
        "application/vnd.ms-powerpoint.template.macroenabled" -> "application/vnd.ms-powerpoint.template.macroenabled.12"
        "application/vnd.ms-powerpoint.presentation.macroenabled" -> "application/vnd.ms-powerpoint.presentation.macroenabled.12"
        "application/vnd.ms-visio.drawing" -> "application/vnd.visio"
        "application/vnd.ms-package.3dmanufacturing-3dmodel+xml" -> "model/3mf"
        else -> null
    }
}
