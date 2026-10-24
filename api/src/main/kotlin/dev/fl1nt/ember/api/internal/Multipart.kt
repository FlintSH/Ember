package dev.fl1nt.ember.api.internal

import java.text.Normalizer

/**
 * The `Content-Disposition` value for a multipart file part.
 *
 * OkHttp writes a non-ASCII name as raw UTF-8, and Flare's multipart parser
 * (busboy 1.6, which Flare runs without `defParamCharset`) reads header
 * parameters as Latin-1, so "café.jpg" was stored as "cafÃ©.jpg". busboy prefers
 * an RFC 5987 `filename*` parameter and decodes its percent-encoded UTF-8, so
 * names that need it get one. The plain `filename` then carries an ASCII
 * approximation for servers that only read that.
 */
internal fun fileDisposition(field: String, fileName: String): String {
    val plain = fileName.all { it.code in 0x20..0x7E && it != '"' && it != '\\' }
    if (plain) return "form-data; name=\"$field\"; filename=\"$fileName\""
    return "form-data; name=\"$field\"; filename=\"${asciiName(fileName)}\"; filename*=UTF-8''${extendedValue(fileName)}"
}

/** Drops accents, then replaces every character that can't appear in a quoted ASCII value. */
private fun asciiName(fileName: String): String {
    val unaccented = Normalizer.normalize(fileName, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
    return buildString {
        var index = 0
        while (index < unaccented.length) {
            val codePoint = unaccented.codePointAt(index)
            append(if (codePoint in 0x20..0x7E && codePoint != '"'.code && codePoint != '\\'.code) codePoint.toChar() else '_')
            index += Character.charCount(codePoint)
        }
    }
}

/** RFC 5987 `ext-value` characters: everything except `attr-char` is percent-encoded UTF-8. */
private fun extendedValue(fileName: String): String = buildString {
    for (byte in fileName.toByteArray(Charsets.UTF_8)) {
        val value = byte.toInt() and 0xFF
        if (value.toChar() in ATTR_CHARS) {
            append(value.toChar())
        } else {
            append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
    }
}

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private const val HEX = "0123456789ABCDEF"
private val ATTR_CHARS: Set<Char> = (('A'..'Z') + ('a'..'z') + ('0'..'9') + "!#\$&+-.^_`|~".toList()).toSet()
