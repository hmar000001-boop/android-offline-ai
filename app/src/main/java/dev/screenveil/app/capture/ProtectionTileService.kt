package dev.screenveil.app.capture

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.screenveil.app.MainActivity
import dev.screenveil.app.diagnostics.DiagnosticLog

/**
 * Quick Settings tile: a one-tap Stop that does not depend on seeing or touching the covered
 * screen. It is deliberately Stop-only. Tapping it while protection is OFF opens the app, where
 * model integrity and the mandatory notification Stop control are checked before Start.
 */
class ProtectionTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh(ProtectionStatus.state.value.active)
    }

    override fun onClick() {
        super.onClick()
        val active = ProtectionStatus.state.value.active
        if (active) {
            ScreenProtectionAccessibilityService.requestStop()
            ProtectionNotifier.clearActive(this)
            DiagnosticLog.record(this, "STOP requested from Quick Settings tile")
            refresh(false)
        } else {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
    }

    private fun refresh(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "ScreenVeil"
        tile.subtitle = if (active) "On - tap to stop" else "Off"
        tile.updateTile()
    }
}
