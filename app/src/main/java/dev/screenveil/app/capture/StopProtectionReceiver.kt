package dev.screenveil.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.screenveil.app.AppPreferences
import dev.screenveil.app.diagnostics.DiagnosticLog

/** Handles the notification's Stop action. Not exported: only ScreenVeil's own PendingIntent reaches it. */
class StopProtectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ProtectionNotifier.ACTION_STOP) return
        AppPreferences(context).setProtectionEnabled(false)
        ScreenProtectionAccessibilityService.requestStop()
        ProtectionNotifier.clearActive(context)
        DiagnosticLog.record(context, "STOP requested from notification")
    }
}
