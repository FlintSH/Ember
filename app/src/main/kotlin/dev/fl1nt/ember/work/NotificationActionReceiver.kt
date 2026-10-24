package dev.fl1nt.ember.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.fl1nt.ember.system.Clipboard

/** Handles "Copy link" on an upload notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY) return
        val link = intent.getStringExtra(EXTRA_LINK) ?: return
        Clipboard.copy(context, link)
    }

    companion object {
        const val ACTION_COPY = "dev.fl1nt.ember.action.COPY_LINK"
        const val EXTRA_LINK = "link"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
