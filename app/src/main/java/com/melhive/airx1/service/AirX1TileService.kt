package com.melhive.airx1.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.melhive.airx1.bus.GestureBus
import com.melhive.airx1.ui.StartActivity

/** Quick Settings tile: turns AirX1 on and off from the notification shade. */
class AirX1TileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (GestureBus.engineRunning.value) {
            HandTrackingService.stop(this)
            qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
        } else {
            // The camera may only be started from the foreground, so open a tiny
            // invisible activity that starts the engine and closes itself.
            val intent = Intent(this, StartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                )
            } else {
                @SuppressLint("StartActivityAndCollapseDeprecated")
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (GestureBus.engineRunning.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
