package dev.n2k02.chargewatt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restores widget polling after boot when Start on boot is enabled. */
class ChargeWattBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!ChargeWattSettings.startOnBoot(context)) return
        if (WidgetUpdater.widgetIds(context).isEmpty()) return
        try {
            WidgetRunState.setPaused(context, false)
            WidgetUpdater.scheduleFallback(context)
            WidgetPollingService.startIfNeeded(context)
        } catch (error: RuntimeException) {
            Log.e("ChargeWattStartup", "Boot restore failed", error)
        }
    }
}
