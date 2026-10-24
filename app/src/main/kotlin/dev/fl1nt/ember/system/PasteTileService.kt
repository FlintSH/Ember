package dev.fl1nt.ember.system

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.fl1nt.ember.ui.share.ShareActivity

/**
 * A Quick Settings tile that turns the clipboard into a paste or short link.
 * Android only lets an app read the clipboard while it's in front, so the tile
 * opens Ember's sheet, which reads it once it has focus.
 */
class PasteTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(::openSheet) else openSheet()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openSheet() {
        val intent = Intent(this, ShareActivity::class.java)
            .setAction(ShareActivity.ACTION_PASTE_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
