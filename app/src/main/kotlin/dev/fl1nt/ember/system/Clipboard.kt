package dev.fl1nt.ember.system

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.core.content.getSystemService
import dev.fl1nt.ember.R

object Clipboard {

    /**
     * Copies [text]. Android 13 and later confirm copies themselves, so Ember only
     * shows its own confirmation on older versions.
     */
    fun copy(context: Context, text: String, label: String = context.getString(R.string.clip_label_link), confirm: Boolean = true) {
        val manager = context.getSystemService<ClipboardManager>() ?: return
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        if (confirm && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.toast_link_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /** Copies a secret without showing it in the clipboard preview. */
    fun copySensitive(context: Context, text: String, label: String) {
        val manager = context.getSystemService<ClipboardManager>() ?: return
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        manager.setPrimaryClip(clip)
    }

    /**
     * Reads the clipboard as text. Android only allows this while Ember is in the
     * foreground with window focus, so call it from a focused activity.
     */
    fun readText(context: Context): String? {
        val manager = context.getSystemService<ClipboardManager>() ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    }
}
