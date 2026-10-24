package dev.fl1nt.ember.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import dev.fl1nt.ember.R

object Links {

    fun open(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, text: String, title: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            title?.let { putExtra(Intent.EXTRA_TITLE, it) }
        }
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

/** Decides what to do with shared or selected text. */
object SharedText {
    private val URL = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)

    /** The URL when the text is nothing but one web address. */
    fun singleUrl(text: String): String? {
        val trimmed = text.trim().removeSurrounding("<", ">").removeSurrounding("\"")
        return trimmed.takeIf { URL.matches(it) && it.length <= 8192 }
    }

    /** Suggests a file name with a useful extension when the content is obvious. */
    fun suggestedName(text: String, fallback: String): String {
        val trimmed = text.trimStart()
        val extension = when {
            (trimmed.startsWith("{") || trimmed.startsWith("[")) && looksLikeJson(trimmed) -> "json"
            trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true) -> "html"
            trimmed.startsWith("#!") -> "sh"
            else -> null
        } ?: return fallback
        return fallback.substringBeforeLast('.', fallback) + "." + extension
    }

    private fun looksLikeJson(text: String): Boolean {
        val end = text.trimEnd()
        return (text.startsWith("{") && end.endsWith("}")) || (text.startsWith("[") && end.endsWith("]"))
    }
}
