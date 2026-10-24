package dev.fl1nt.ember.api

/**
 * Chooses the MIME type Ember claims for an upload.
 *
 * Flare checks a file's leading bytes against the claimed type and rejects a
 * mismatch, so claiming `application/octet-stream` for a PNG fails. Content
 * providers sometimes report nothing or a wrong type. [resolve] sniffs common
 * signatures and applies the same equivalence rules as Flare's
 * `lib/security/file-validation.ts`, preferring the most specific type that the
 * server will accept.
 */
object MimeTypes {
    const val OCTET_STREAM = "application/octet-stream"

    /** Bytes [sniff] needs. Matches the window Flare's detector reads. */
    const val HEADER_BYTES = 4100

    fun resolve(declared: String?, header: ByteArray, fileName: String): String {
        val claimed = declared?.normalized()?.takeIf { it.isSpecific() }
        val byExtension = fromExtension(fileName)
        val sniffed = sniff(header) ?: return claimed ?: byExtension ?: OCTET_STREAM
        return when {
            claimed != null && matches(sniffed, claimed) -> claimed
            byExtension != null && matches(sniffed, byExtension) -> byExtension
            else -> sniffed
        }
    }

    /** Port of Flare's `mimeTypesMatch(detected, claimed)`. */
    fun matches(detected: String, claimed: String): Boolean {
        val a = detected.normalized()
        val b = claimed.normalized()
        if (a == b) return true
        if (EQUIVALENCE_GROUPS.any { a in it && b in it }) return true
        if (a in ZIP_TYPES && b in ZIP_CONTAINER_FORMATS) return true
        if (b in ZIP_TYPES && a in ZIP_CONTAINER_FORMATS) return true
        return FLEXIBLE_PREFIXES.any { a.startsWith(it) && b.startsWith(it) }
    }

    fun fromExtension(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return null
        return EXTENSIONS[fileName.substring(dot + 1).lowercase()]
    }

    /** Detects strong binary signatures. Text formats are left to the declared type. */
    fun sniff(header: ByteArray): String? {
        val h = header
        fun at(offset: Int, vararg bytes: Int): Boolean =
            h.size >= offset + bytes.size && bytes.indices.all { (h[offset + it].toInt() and 0xFF) == bytes[it] }
        fun ascii(offset: Int, text: String): Boolean =
            h.size >= offset + text.length && text.indices.all { h[offset + it].toInt() == text[it].code }

        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
            ascii(0, "RIFF") && ascii(8, "AVI ") -> "video/vnd.avi"
            ascii(4, "ftyp") -> isoMediaType(h)
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> if (containsAscii(h, "webm", 64)) "video/webm" else "video/x-matroska"
            ascii(0, "%PDF-") -> "application/pdf"
            at(0, 0x50, 0x4B, 0x03, 0x04) -> "application/zip"
            at(0, 0x1F, 0x8B, 0x08) -> "application/gzip"
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "application/x-7z-compressed"
            ascii(0, "Rar!") && at(4, 0x1A, 0x07) -> "application/x-rar-compressed"
            at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> "application/x-xz"
            ascii(0, "BZh") && h.size > 3 && h[3].toInt() in '1'.code..'9'.code -> "application/x-bzip2"
            ascii(257, "ustar") -> "application/x-tar"
            ascii(0, "ID3") -> "audio/mpeg"
            at(0, 0xFF) && h.size > 1 && (h[1].toInt() and 0xF6) == 0xF0 -> "audio/aac"
            at(0, 0xFF) && h.size > 1 && (h[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
            ascii(0, "OggS") -> "audio/ogg"
            ascii(0, "fLaC") -> "audio/x-flac"
            ascii(0, "#!AMR") -> "audio/amr"
            ascii(0, "MThd") -> "audio/midi"
            ascii(0, "II*") && at(3, 0x00) || ascii(0, "MM") && at(2, 0x00, 0x2A) -> "image/tiff"
            at(0, 0x00, 0x00, 0x01, 0x00) && h.size > 5 && h[4].toInt() != 0 -> "image/x-icon"
            ascii(0, "BM") && at(6, 0x00, 0x00, 0x00, 0x00) -> "image/bmp"
            ascii(0, "8BPS") -> "image/vnd.adobe.photoshop"
            at(0, 0xFF, 0x0A) || at(0, 0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20) -> "image/jxl"
            ascii(0, "SQLite format 3") -> "application/x-sqlite3"
            at(0, 0x7F, 0x45, 0x4C, 0x46) -> "application/x-elf"
            at(0, 0x00, 0x61, 0x73, 0x6D) -> "application/wasm"
            else -> null
        }
    }

    private fun isoMediaType(h: ByteArray): String? {
        if (h.size < 12) return null
        val brand = String(h, 8, 4, Charsets.ISO_8859_1).trim().lowercase()
        return when (brand) {
            "heic", "heix", "hevc", "hevx", "heim", "heis" -> "image/heic"
            "mif1", "msf1" -> "image/heif"
            "avif", "avis" -> "image/avif"
            "qt" -> "video/quicktime"
            "m4a" -> "audio/x-m4a"
            "m4v" -> "video/x-m4v"
            "m4p", "m4b", "f4a", "f4b" -> "audio/mp4"
            "crx" -> null
            else -> when {
                brand.startsWith("3g2") -> "video/3gpp2"
                brand.startsWith("3g") -> "video/3gpp"
                else -> "video/mp4"
            }
        }
    }

    private fun containsAscii(h: ByteArray, text: String, within: Int): Boolean {
        val limit = minOf(h.size, within) - text.length
        return (0..limit).any { start -> text.indices.all { h[start + it].toInt() == text[it].code } }
    }

    private fun String.normalized(): String = substringBefore(';').trim().lowercase()

    private fun String.isSpecific(): Boolean =
        contains('/') && this != OCTET_STREAM && !endsWith("/*") && this != "application/unknown"

    private val FLEXIBLE_PREFIXES = listOf("video/", "audio/")

    private val EQUIVALENCE_GROUPS: List<Set<String>> = listOf(
        setOf("application/zip", "application/x-zip-compressed", "application/x-zip", "multipart/x-zip"),
        setOf("application/gzip", "application/x-gzip", "application/x-gunzip"),
        setOf("application/x-7z-compressed", "application/x-7z"),
        setOf("application/x-rar-compressed", "application/vnd.rar", "application/x-rar"),
        setOf("application/x-tar", "application/tar", "application/x-gtar"),
        setOf("application/x-bzip2", "application/bzip2"),
        setOf("image/jpeg", "image/jpg", "image/pjpeg"),
        setOf("image/x-icon", "image/vnd.microsoft.icon"),
        setOf("image/heic", "image/heif"),
        setOf("application/xml", "text/xml", "image/svg+xml"),
        setOf("audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg-3"),
        setOf(
            "video/mp4", "video/quicktime", "video/x-m4v", "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac",
        ),
        setOf(
            "application/x-msdownload",
            "application/vnd.microsoft.portable-executable",
            "application/x-dosexec",
        ),
    )

    private val ZIP_TYPES = setOf(
        "application/zip", "application/x-zip-compressed", "application/x-zip", "multipart/x-zip",
    )

    private val ZIP_CONTAINER_FORMATS = setOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/vnd.oasis.opendocument.graphics",
        "application/epub+zip",
        "application/java-archive",
        "application/vnd.android.package-archive",
    )

    private val EXTENSIONS: Map<String, String> = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "ico" to "image/x-icon",
        "jxl" to "image/jxl",
        "dng" to "image/x-adobe-dng",
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "3gp" to "video/3gpp",
        "avi" to "video/vnd.avi",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/x-m4a",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "opus" to "audio/ogg",
        "flac" to "audio/x-flac",
        "wav" to "audio/wav",
        "amr" to "audio/amr",
        "mid" to "audio/midi",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "gz" to "application/gzip",
        "tgz" to "application/gzip",
        "tar" to "application/x-tar",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/x-rar-compressed",
        "xz" to "application/x-xz",
        "bz2" to "application/x-bzip2",
        "apk" to "application/vnd.android.package-archive",
        "jar" to "application/java-archive",
        "epub" to "application/epub+zip",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "txt" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "application/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "text/javascript",
        "ts" to "text/typescript",
        "kt" to "text/x-kotlin",
        "java" to "text/x-java",
        "py" to "text/x-python",
        "sh" to "application/x-sh",
        "yaml" to "application/yaml",
        "yml" to "application/yaml",
        "toml" to "application/toml",
        "sqlite" to "application/x-sqlite3",
        "db" to "application/x-sqlite3",
        "wasm" to "application/wasm",
    )
}
