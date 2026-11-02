package dev.n2k02.chargewatt

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
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
 * Charger broadcasts and WorkManager remain fallbacks, not 1-second guarantees.
 */
class WidgetPollingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var polling: Job? = null
    private var pendingPowerRefresh: Job? = null
    private val sourceTracker = WidgetPowerStateTracker()
    private var receiverRegistered = false

    // Unlike a manifest receiver, a dynamically registered receiver receives
    // ACTION_BATTERY_CHANGED while this process/service is alive.
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (WidgetRunState.isPaused(applicationContext)) return
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    BatterySampler.remember(intent)
                    val source = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                    if (source >= 0 && sourceTracker.changed(source)) requestPowerRefresh()
                }
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED ->
                    requestPowerRefresh()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(batteryReceiver, filter)
            }
            receiverRegistered = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Live battery receiver registration failed", error)
        }
    }

    private fun requestPowerRefresh() {
        pendingPowerRefresh?.cancel()
        pendingPowerRefresh = scope.launch {
            // Android may send POWER_CONNECTED before the battery snapshot reflects
            // the actual source; the delayed second read catches that transition.
            delay(200L)
            if (!WidgetRunState.isPaused(applicationContext)) {
                try {
                    BatterySampler.clear()
                    WidgetUpdater.refresh(applicationContext, force = true)
                    delay(1_200L)
                    if (!WidgetRunState.isPaused(applicationContext)) {
                        BatterySampler.clear()
                        WidgetUpdater.refresh(applicationContext, force = true)
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Power-source widget refresh failed", error)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (WidgetRunState.isPaused(this)) {
            ChargeOverlay.stop()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ChargeOverlay.start(this)
        if (WidgetUpdater.widgetIds(this).isEmpty() && !ChargeOverlay.isRunning) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (polling?.isActive != true) {
            polling = scope.launch {
                while (isActive) {
                    if (WidgetRunState.isPaused(applicationContext) ||
                        (WidgetUpdater.widgetIds(applicationContext).isEmpty() && !ChargeOverlay.isRunning)) {
                        stopSelf()
                        break
                    }
                    // Activity already polls while resumed; avoid duplicate work.
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
        if (receiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Battery receiver cleanup failed", error)
            }
            receiverRegistered = false
        }
        scope.cancel()
        ChargeOverlay.stop()
        polling = null
        pendingPowerRefresh = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ChargeWattPolling"

        /** Must be called from a visible Activity, not from a background receiver. */
        fun startWhileVisible(context: Context) {
            if (WidgetRunState.isPaused(context) || (WidgetUpdater.widgetIds(context).isEmpty() && !ChargeOverlay.isRunning)) return
            try {
                context.startService(Intent(context, WidgetPollingService::class.java))
            } catch (error: RuntimeException) {
                Log.w(TAG, "Background service start unavailable", error)
            }
        }

        fun stop(context: Context) {
            ChargeOverlay.stop()
            try {
                context.stopService(Intent(context, WidgetPollingService::class.java))
            } catch (error: RuntimeException) {
                Log.w(TAG, "Background service stop failed", error)
            }
        }
    }
}

/** Only changing plugged flags justify a forced widget update. */
internal class WidgetPowerStateTracker {
    private var lastSource: Int? = null
    fun changed(source: Int): Boolean {
        if (lastSource == source) return false
        lastSource = source
        return true
    }
}

/** Avoids running two independent 1-10 second battery sampling loops. */
internal object WidgetPollingActivityState {
    @Volatile var resumed: Boolean = false
}
