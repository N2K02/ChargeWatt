package dev.n2k02.chargewatt

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** No persistent service or notification; only event broadcasts and OS-scheduled periodic work. */
class ChargeWattWidget : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdater.scheduleFallback(context)
    }
    override fun onDisabled(context: Context) {
        WidgetUpdater.cancelFallback(context)
        super.onDisabled(context)
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.refresh(context, force = true)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BATTERY_LOW,
            Intent.ACTION_BATTERY_OKAY,
            Intent.ACTION_BOOT_COMPLETED -> {
                WidgetUpdater.refresh(context, force = true)
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) WidgetUpdater.scheduleFallback(context)
            }
        }
    }
}

class ChargeWattWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        WidgetUpdater.refresh(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

object WidgetUpdater {
    private const val WORK = "chargewatt-widget-fallback"
    fun widgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, ChargeWattWidget::class.java))
    fun scheduleFallback(context: Context) {
        if (widgetIds(context).isEmpty()) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ChargeWattWorker>(15, TimeUnit.MINUTES).build(),
        )
    }
    fun cancelFallback(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
    fun refresh(context: Context, reading: BatteryReading = BatterySampler.read(context), force: Boolean = false) {
        val ids = widgetIds(context)
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        val theme = ChargeWattSettings.theme(context)
        val unit = ChargeWattSettings.unit(context)
        val fingerprint = reading.fingerprint(unit, theme)
        val prefs = context.getSharedPreferences("widget_cache", Context.MODE_PRIVATE)
        for (id in ids) {
            val key = "widget_$id"
            if (!force && fingerprint == prefs.getString(key, null)) continue
            val layout = if (theme == "transparent") R.layout.widget_transparent else R.layout.widget_dark
            val views = RemoteViews(context.packageName, layout)
            views.setTextViewText(R.id.widget_value, if (unit == "mA") reading.signedCurrent else reading.signedWatts)
            views.setTextViewText(R.id.widget_sub, if (unit == "mA") reading.signedWatts else reading.signedCurrent)
            views.setTextViewText(R.id.widget_voltage, reading.voltageText)
            views.setTextViewText(R.id.widget_level, reading.levelText)
            val active = reading.mode == ChargeMode.CHARGING
            val color = when (reading.mode) {
                ChargeMode.CHARGING -> Color.rgb(94, 240, 140)
                ChargeMode.DISCHARGING -> Color.rgb(255, 183, 75)
                else -> Color.WHITE
            }
            views.setTextColor(R.id.widget_value, color)
            views.setTextViewText(R.id.widget_status, if (active) "⚡" else if (reading.mode == ChargeMode.DISCHARGING) "↓" else "•")
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            manager.updateAppWidget(id, views)
            prefs.edit().putString(key, fingerprint).apply()
        }
    }
}
