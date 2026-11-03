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
    companion object { private const val TAG = "ChargeWattPower" }

    override fun onReceive(context: Context, intent: Intent) {
        if (!PowerConnectionPolicy.shouldRefresh(intent.action)) return
        if (WidgetRunState.isPaused(context)) return

        val pending = goAsync()
        val appContext = context.applicationContext
        try {
            executor.execute {
                try {
                    Log.i(TAG, "Power event action=${intent.action}; ${BatterySampler.describeFreshSnapshot(appContext)}")
                    // BatteryManager may publish the new source shortly after the power event.
                    // Read a fresh sticky snapshot at 0s, 1.5s, and 3s.
                    for (attempt in 0..2) {
                        if (attempt > 0) Thread.sleep(1_500L)
                        if (WidgetRunState.isPaused(appContext)) break
                        BatterySampler.clear()
                        Log.i(TAG, "Power refresh attempt=${attempt + 1}; ${BatterySampler.describeFreshSnapshot(appContext)}")
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
