package dev.fl1nt.ember.api

import dev.fl1nt.ember.api.internal.FileTypeSniffer

/**
 * Chooses the MIME type Ember claims for an upload.
 *
 * Flare reads a file's first 4100 bytes with the `file-type` package and rejects
 * a claimed type that doesn't match what it detects, so claiming
 * `application/octet-stream` for a PNG fails. Content providers sometimes report
 * nothing, a generic type, or a spelling Flare doesn't accept. [sniff] repeats
 * Flare's detection and [resolve] applies the equivalence rules of Flare's
 * `lib/security/file-validation.ts`, keeping the most specific type the server
 * will accept.
 */
object MimeTypes {
    const val OCTET_STREAM = "application/octet-stream"

    /** Bytes [sniff] needs. Matches the window Flare's detector reads. */
    const val HEADER_BYTES = 4100

    /**
     * The declared type when Flare will accept it, then the extension's, then the
     * detected one. Detection often names only a container, such as ZIP for an
     * APK or MP4 for an M4A recording, so a consistent declared type is kept.
     */
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

    /**
     * The type Flare's detector reports for a file starting with [header], or
     * `null` when it reports none or Ember can't tell. Ordinary text gets `null`,
     * so its declared type is used.
     */
    fun sniff(header: ByteArray): String? = FileTypeSniffer.detect(header)

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
        "apng" to "image/apng",
        // Raw photos, spelled the way Flare's detector names them.
        "dng" to "image/x-adobe-dng",
        "cr2" to "image/x-canon-cr2",
        "cr3" to "image/x-canon-cr3",
        "nef" to "image/x-nikon-nef",
        "arw" to "image/x-sony-arw",
        "orf" to "image/x-olympus-orf",
        "rw2" to "image/x-panasonic-rw2",
        "raf" to "image/x-fujifilm-raf",
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "3gp" to "video/3gpp",
        "3g2" to "video/3gpp2",
        "ogv" to "video/ogg",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "avi" to "video/vnd.avi",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/x-m4a",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/ogg",
        "flac" to "audio/flac",
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
        "vcf" to "text/vcard",
        "ics" to "text/calendar",
        "vtt" to "text/vtt",
        "rtf" to "application/rtf",
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
        "ttf" to "font/ttf",
    )
}
