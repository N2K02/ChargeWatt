package dev.n2k02.chargewatt

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Best-effort, notification-free polling while Android permits background services.
 *
 * Not a foreground service: Android can stop it after the app becomes idle.
 * PowerConnectionReceiver and WorkManager remain the durable fallbacks.
 */
class WidgetPollingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var polling: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (WidgetRunState.isPaused(this) || WidgetUpdater.widgetIds(this).isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (polling?.isActive != true) {
            polling = scope.launch {
                while (isActive) {
                    if (WidgetRunState.isPaused(applicationContext) ||
                        WidgetUpdater.widgetIds(applicationContext).isEmpty()) {
                        stopSelf()
                        break
                    }
                    // The Activity already samples and updates while resumed.
                    // Avoid redundant work when both components coexist.
                    if (!WidgetPollingActivityState.resumed) {
                        try {
                            WidgetUpdater.refresh(applicationContext)
                        } catch (error: Exception) {
                            Log.w(TAG, "Background widget sample failed", error)
                        }
                    }
                    delay(ChargeWattSettings.interval(applicationContext) * 1000L)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        polling = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ChargeWattPolling"

        /** Must be called from a visible Activity, not from a background receiver. */
        fun startWhileVisible(context: Context) {
            if (WidgetRunState.isPaused(context) || WidgetUpdater.widgetIds(context).isEmpty()) return
            try {
                context.startService(Intent(context, WidgetPollingService::class.java))
            } catch (error: RuntimeException) {
                // Normal on devices restricting background work; other update paths survive.
                Log.w(TAG, "Background service start unavailable", error)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, WidgetPollingService::class.java))
            } catch (error: RuntimeException) {
                Log.w(TAG, "Background service stop failed", error)
            }
        }
    }
}

/** Avoids running two independent 1-10 second battery sampling loops. */
internal object WidgetPollingActivityState {
    @Volatile var resumed: Boolean = false
}
