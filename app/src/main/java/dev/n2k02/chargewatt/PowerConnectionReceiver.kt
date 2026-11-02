package dev.n2k02.chargewatt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

/**
 * Refresh widgets when external power is connected or disconnected, even if
 * the dashboard is not open. Does not start a long-running background service.
 */
class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!PowerConnectionPolicy.shouldRefresh(intent.action)) return
        if (WidgetRunState.isPaused(context)) return

        val pending = goAsync()
        val appContext = context.applicationContext
        try {
            executor.execute {
                try {
                    // Read a fresh sticky battery snapshot, not the foreground
                    // activity's cached ACTION_BATTERY_CHANGED intent.
                    BatterySampler.clear()
                    WidgetUpdater.refresh(appContext, force = true)
                    // Power events may precede the updated battery snapshot.
                    for (attempt in 0 until 2) {
                        Thread.sleep(1_500L)
                        if (WidgetRunState.isPaused(appContext)) break
                        BatterySampler.clear()
                        WidgetUpdater.refresh(appContext, force = true)
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: Exception) {
                    Log.e("chargeWatt", "Power connection widget refresh failed", error)
                } finally {
                    pending.finish()
                }
            }
        } catch (error: RuntimeException) {
            Log.e("chargeWatt", "Unable to dispatch power connection refresh", error)
            pending.finish()
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "chargewatt-power-events").apply { isDaemon = true }
        }
    }
}

internal object PowerConnectionPolicy {
    fun shouldRefresh(action: String?): Boolean =
        action == Intent.ACTION_POWER_CONNECTED || action == Intent.ACTION_POWER_DISCONNECTED
}
