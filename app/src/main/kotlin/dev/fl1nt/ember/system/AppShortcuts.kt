package dev.fl1nt.ember.system

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.fl1nt.ember.MainActivity
import dev.fl1nt.ember.R

/**
 * Long-press launcher shortcuts. They're published at runtime because static
 * shortcut XML hard-codes the package name, which differs between the stable,
 * rolling, and debug builds.
 */
object AppShortcuts {

    fun publish(context: Context) {
        val shortcuts = listOf(
            shortcut(context, "paste", R.string.shortcut_paste, R.string.shortcut_paste_long, R.drawable.ic_shortcut_paste, MainActivity.DESTINATION_PASTE),
            shortcut(context, "shorten", R.string.shortcut_shorten, R.string.shortcut_shorten_long, R.drawable.ic_shortcut_link, MainActivity.DESTINATION_SHORTEN),
            shortcut(context, "upload", R.string.shortcut_upload, R.string.shortcut_upload_long, R.drawable.ic_shortcut_upload, MainActivity.DESTINATION_UPLOAD),
            shortcut(context, "sync", R.string.shortcut_sync, R.string.shortcut_sync_long, R.drawable.ic_shortcut_sync, MainActivity.DESTINATION_SYNC_NOW),
        )
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    fun clear(context: Context) {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }

    private fun shortcut(context: Context, id: String, label: Int, longLabel: Int, icon: Int, destination: String) =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(label))
            .setLongLabel(context.getString(longLabel))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(MainActivity.EXTRA_DESTINATION, destination)
                    .putExtra(MainActivity.EXTRA_SHORTCUT_ID, id),
            )
            .build()
}
