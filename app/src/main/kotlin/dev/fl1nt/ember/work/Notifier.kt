package dev.fl1nt.ember.work

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.ForegroundInfo
import dev.fl1nt.ember.MainActivity
import dev.fl1nt.ember.R

/** Why Ember needs someone to look at it. */
enum class Attention { TOKEN_REJECTED, LOCAL_NETWORK, FOLDER_ACCESS, MEDIA_ACCESS }

class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val channels = listOf(
            channel(CHANNEL_PROGRESS, R.string.channel_progress, R.string.channel_progress_description, NotificationManagerCompat.IMPORTANCE_LOW),
            channel(CHANNEL_RESULTS, R.string.channel_results, R.string.channel_results_description, NotificationManagerCompat.IMPORTANCE_DEFAULT),
            channel(CHANNEL_SYNC, R.string.channel_sync, R.string.channel_sync_description, NotificationManagerCompat.IMPORTANCE_LOW),
            channel(CHANNEL_ATTENTION, R.string.channel_attention, R.string.channel_attention_description, NotificationManagerCompat.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannelsCompat(channels)
    }

    fun uploadForeground(fileName: String, sent: Long, total: Long, remaining: Int): ForegroundInfo =
        foreground(ID_UPLOAD_PROGRESS, uploadProgress(fileName, sent, total, remaining))

    fun syncForeground(done: Int, total: Int, current: String?): ForegroundInfo =
        foreground(ID_SYNC_PROGRESS, syncProgress(done, total, current))

    fun showUploadProgress(fileName: String, sent: Long, total: Long, remaining: Int) =
        post(ID_UPLOAD_PROGRESS, uploadProgress(fileName, sent, total, remaining))

    fun showSyncProgress(done: Int, total: Int, current: String?) = post(ID_SYNC_PROGRESS, syncProgress(done, total, current))

    fun cancel(id: Int) = manager.cancel(id)

    fun uploaded(uploadId: String, fileName: String, link: String) {
        val id = resultId(uploadId)
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_ember_notification)
            .setContentTitle(context.getString(R.string.notification_uploaded_title, fileName))
            .setContentText(link)
            .setStyle(NotificationCompat.BigTextStyle().bigText(link))
            .setContentIntent(openLink(link, id))
            .addAction(0, context.getString(R.string.action_copy_link), copyLink(link, id))
            .addAction(0, context.getString(R.string.action_share), shareLink(link, id))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setGroup(GROUP_RESULTS)
            .build()
        post(id, notification)
    }

    fun uploadFailed(uploadId: String, fileName: String, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_ember_notification)
            .setContentTitle(context.getString(R.string.notification_failed_title, fileName))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp(MainActivity.DESTINATION_RECENT))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setGroup(GROUP_RESULTS)
            .build()
        post(resultId(uploadId), notification)
    }

    fun syncSummary(uploaded: Int, failed: Int) {
        if (uploaded == 0 && failed == 0) return
        val title = context.resources.getQuantityString(R.plurals.notification_sync_summary, uploaded, uploaded)
        val text = if (failed > 0) context.resources.getQuantityString(R.plurals.notification_sync_failed, failed, failed) else null
        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_ember_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(MainActivity.DESTINATION_SYNC))
            .setAutoCancel(true)
            .build()
        post(ID_SYNC_SUMMARY, notification)
    }

    fun attention(reason: Attention, detail: String? = null) {
        val (title, text) = when (reason) {
            Attention.TOKEN_REJECTED -> R.string.attention_token_title to R.string.attention_token_text
            Attention.LOCAL_NETWORK -> R.string.attention_local_network_title to R.string.attention_local_network_text
            Attention.FOLDER_ACCESS -> R.string.attention_folder_title to R.string.attention_folder_text
            Attention.MEDIA_ACCESS -> R.string.attention_media_title to R.string.attention_media_text
        }
        val body = if (reason == Attention.FOLDER_ACCESS) context.getString(text, detail.orEmpty()) else context.getString(text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_ember_notification)
            .setContentTitle(context.getString(title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(if (reason == Attention.TOKEN_REJECTED) MainActivity.DESTINATION_SETTINGS else MainActivity.DESTINATION_SYNC))
            // Scheduled syncs repeat the check; only the first one should make a sound.
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        post(ID_ATTENTION, notification)
    }

    private fun uploadProgress(fileName: String, sent: Long, total: Long, remaining: Int): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_ember_notification)
            .setContentTitle(context.getString(R.string.notification_uploading_title, fileName))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(MainActivity.DESTINATION_RECENT))
        if (remaining > 0) builder.setContentText(context.resources.getQuantityString(R.plurals.notification_more_queued, remaining, remaining))
        if (total > 0) {
            val percent = ((sent * 100) / total).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false).setSubText("$percent%")
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun syncProgress(done: Int, total: Int, current: String?): Notification =
        NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_ember_notification)
            .setContentTitle(context.getString(R.string.notification_syncing_title, done, total))
            .setContentText(current)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(MainActivity.DESTINATION_SYNC))
            .build()

    private fun foreground(id: Int, notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }

    // Posting is a no-op when someone turned notifications off; uploads still work.
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the post.
        }
    }

    private fun openApp(destination: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_DESTINATION, destination)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, destination.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openLink(link: String, requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, link.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun copyLink(link: String, notificationId: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_COPY)
            .putExtra(NotificationActionReceiver.EXTRA_LINK, link)
            .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        return PendingIntent.getBroadcast(context, notificationId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun shareLink(link: String, requestCode: Int): PendingIntent {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode + 1, chooser, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun channel(id: String, name: Int, description: Int, importance: Int) =
        NotificationChannelCompat.Builder(id, importance)
            .setName(context.getString(name))
            .setDescription(context.getString(description))
            .build()

    private fun resultId(uploadId: String) = RESULT_BASE + (uploadId.hashCode() and 0xFFFFF)

    companion object {
        const val CHANNEL_PROGRESS = "uploads"
        const val CHANNEL_RESULTS = "results"
        const val CHANNEL_SYNC = "sync"
        const val CHANNEL_ATTENTION = "attention"
        const val ID_UPLOAD_PROGRESS = 1001
        const val ID_SYNC_PROGRESS = 1002
        const val ID_ATTENTION = 1003
        const val ID_SYNC_SUMMARY = 1004
        private const val RESULT_BASE = 0x100000
        private const val GROUP_RESULTS = "dev.fl1nt.ember.results"
    }
}
