package com.metrolist.music.quicksettings

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.metrolist.music.MainActivity
import com.metrolist.music.R

class MusicRecognizerTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            icon = Icon.createWithResource(this@MusicRecognizerTileService, R.drawable.mic)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()

        val launchIntent =
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_RECOGNITION
                putExtra(MainActivity.EXTRA_AUTO_START_RECOGNITION, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        // startActivityAndCollapse(Intent) was deprecated in API 34 in favour of the
        // PendingIntent overload, which collapses the Quick Settings panel reliably.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent =
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }
}
