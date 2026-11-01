package dev.n2k02.chargewatt

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** An installed widget uses periodic OS-managed work, not a continuously running service. */
class ChargeWattWidget : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // Placing a new widget is an explicit request to restart widget updates.
        WidgetRunState.setPaused(context, false)
        WidgetUpdater.invalidateWidgetIds()
        WidgetUpdater.scheduleFallback(context)
    }
    override fun onDisabled(context: Context) {
        WidgetUpdater.invalidateWidgetIds()
        WidgetUpdater.cancelFallback(context)
        WidgetUpdater.clearAll()
        super.onDisabled(context)
    }
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetUpdater.invalidateWidgetIds()
        WidgetUpdater.clearDeleted(appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        if (WidgetRunState.isPaused(context)) return
        WidgetUpdater.invalidateWidgetIds()
        WidgetUpdater.scheduleFallback(context)
        WidgetUpdater.refresh(context, force = true)
    }
}

class ChargeWattWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        WidgetUpdater.refresh(applicationContext)
        Result.success()
    } catch (error: Exception) {
        // A device missing this optional telemetry must not enter a retry storm.
        Log.w("chargeWatt", "Deferred widget refresh failed; next cycle will check", error)
        Result.success()
    }
}

/** Quit is persistent: Android widget-provider events must never resurrect the polling job. */
object WidgetRunState {
    private const val FILE = "chargewatt_runner"
    fun isPaused(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("paused", false)
    fun setPaused(context: Context, paused: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("paused", paused).apply()
    }
}

/** Pure policy for deciding when to use expensive full updates. */
object WidgetUpdatePolicy {
    fun needsFull(force: Boolean, themeChanged: Boolean, lastFullAt: Long?, now: Long, maxAgeMs: Long = 60_000L): Boolean =
        force || themeChanged || lastFullAt == null || now < lastFullAt || now - lastFullAt >= maxAgeMs
}

/**
 * In-process update cache. Never persist widget fingerprints every second:
 * that causes continual SharedPreferences disk writes and extra wakeups.
 */
object WidgetUpdater {
    private const val WORK = "chargewatt-widget-fallback"
    private val fingerprints = mutableMapOf<Int, String>()
    private val themes = mutableMapOf<Int, String>()
    private val lastFull = mutableMapOf<Int, Long>()
    private var cachedWidgetIds: IntArray? = null

    /** Widget-host inventory changes invalidate the cache; do not query Binder at 1Hz. */
    @Synchronized fun invalidateWidgetIds() { cachedWidgetIds = null }
    @Synchronized fun widgetIds(context: Context): IntArray {
        val cached = cachedWidgetIds
        if (cached != null) return cached.clone()
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ChargeWattWidget::class.java))
        cachedWidgetIds = ids
        return ids.clone()
    }

    fun scheduleFallback(context: Context) {
        if (WidgetRunState.isPaused(context) || widgetIds(context).isEmpty()) return
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ChargeWattWorker>(15, TimeUnit.MINUTES).build(),
        )
    }
    fun cancelFallback(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
    }
    @Synchronized fun clearDeleted(ids: IntArray) {
        ids.forEach {
            fingerprints.remove(it)
            themes.remove(it)
            lastFull.remove(it)
        }
    }
    @Synchronized fun clearAll() {
        fingerprints.clear()
        themes.clear()
        lastFull.clear()
    }

    /**
     * First update is always full (necessary before Android accepts partial updates).
     * Partial updates contain text/color actions only, no bitmap or icon allocations.
     * A full replacement every 60 seconds caps cached RemoteViews action growth.
     */
    @Synchronized fun refresh(context: Context, reading: BatteryReading? = null, force: Boolean = false) {
        if (WidgetRunState.isPaused(context)) return
        val ids = widgetIds(context)
        if (ids.isEmpty()) return // No widget => no battery-system call.
        val value = reading ?: BatterySampler.read(context)
        val theme = ChargeWattSettings.theme(context)
        val unit = ChargeWattSettings.unit(context)
        val fingerprint = value.fingerprint(unit, theme)
        val now = SystemClock.elapsedRealtime()
        val full = mutableListOf<Int>()
        val partial = mutableListOf<Int>()
        for (id in ids) {
            if (!force && fingerprint == fingerprints[id]) continue
            if (WidgetUpdatePolicy.needsFull(force, themes[id] != theme, lastFull[id], now)) {
                full.add(id)
                lastFull[id] = now
            } else {
                partial.add(id)
            }
            fingerprints[id] = fingerprint
            themes[id] = theme
        }
        if (full.isEmpty() && partial.isEmpty()) return
        val layout = if (theme == "transparent") R.layout.widget_transparent else R.layout.widget_dark
        val views = RemoteViews(context.packageName, layout)
        views.setTextViewText(R.id.widget_value, if (unit == "mA") value.signedCurrent else value.signedWatts)
        views.setTextViewText(R.id.widget_sub, if (unit == "mA") value.signedWatts else value.signedCurrent)
        views.setTextViewText(R.id.widget_voltage, value.voltageText)
        views.setTextViewText(R.id.widget_level, value.levelText)
        val draining = when {
            value.currentMa != null -> value.currentMa < 0.0
            else -> value.mode == ChargeMode.DISCHARGING
        }
        views.setTextColor(R.id.widget_value, if (draining) Color.rgb(255, 183, 75) else Color.rgb(94, 240, 140))
        views.setTextViewText(R.id.widget_status,
            if (draining) "↓" else if (value.mode == ChargeMode.CHARGING) "⚡" else "•")
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Only a full render needs the click action; partial updates remain text-only.
        if (full.isNotEmpty()) views.setOnClickPendingIntent(R.id.widget_root, pending)
        val manager = AppWidgetManager.getInstance(context)
        if (full.isNotEmpty()) manager.updateAppWidget(full.toIntArray(), views)
        if (partial.isNotEmpty()) manager.partiallyUpdateAppWidget(partial.toIntArray(), views)
    }
}
