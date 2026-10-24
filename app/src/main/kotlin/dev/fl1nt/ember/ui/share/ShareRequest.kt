package dev.fl1nt.ember.ui.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** What another app handed to Ember. */
sealed interface ShareRequest {
    data class Files(val uris: List<Uri>, val rejected: Int = 0) : ShareRequest

    data class Text(val text: String, val mode: TextMode, val readOnly: Boolean = true) : ShareRequest

    /** The Quick Settings tile: read the clipboard once the sheet has focus. */
    data object Clipboard : ShareRequest

    /** Nothing Ember can use, such as an empty share or only `file://` paths. */
    data class Unsupported(val rejectedFiles: Int = 0) : ShareRequest

    enum class TextMode { SHARE, SELECTION, CLIPBOARD }

    companion object {
        const val MAX_FILES = 100

        fun from(intent: Intent?): ShareRequest {
            intent ?: return Unsupported()
            return when (intent.action) {
                Intent.ACTION_SEND -> {
                    val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    val clipUris = clipUris(intent)
                    val uris = listOfNotNull(stream) + clipUris.filter { it != stream }
                    if (uris.isNotEmpty()) {
                        files(uris)
                    } else {
                        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                        if (text.isNullOrBlank()) Unsupported() else Text(text, TextMode.SHARE)
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                    files((streams + clipUris(intent)).distinct())
                }
                Intent.ACTION_PROCESS_TEXT -> {
                    val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                    val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                    if (text.isNullOrBlank()) Unsupported() else Text(text, TextMode.SELECTION, readOnly)
                }
                ShareActivity.ACTION_PASTE_CLIPBOARD -> Clipboard
                else -> Unsupported()
            }
        }

        private fun clipUris(intent: Intent): List<Uri> {
            val clip = intent.clipData ?: return emptyList()
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }

        /**
         * Keeps content URIs only. A `file://` path would be read with Ember's own
         * permissions, which could expose Ember's private files; Android has
         * required content URIs for sharing since Android 7.
         */
        private fun files(uris: List<Uri>): ShareRequest {
            val accepted = uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT }.distinct().take(MAX_FILES)
            val rejected = uris.size - accepted.size
            return if (accepted.isEmpty()) Unsupported(rejected) else Files(accepted, rejected)
        }
    }
}
