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
import kotlin.math.roundToInt

/** Installed widget lifecycle owns its periodic fallback and sticky foreground sampler. */
class ChargeWattWidget : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // A launcher widget must never crash the host due to one scheduler error.
        try {
            WidgetRunState.setPaused(context, false)
            WidgetUpdater.invalidateWidgetIds()
            WidgetUpdater.scheduleFallback(context)
            WidgetPollingService.startIfNeeded(context)
        } catch (error: RuntimeException) {
            Log.e("ChargeWattStartup", "Widget enable failed", error)
        }
    }
    override fun onDisabled(context: Context) {
        WidgetUpdater.invalidateWidgetIds()
        WidgetUpdater.cancelFallback(context)
        WidgetUpdater.clearAll()
        WidgetPollingService.stop(context)
        super.onDisabled(context)
    }
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetUpdater.invalidateWidgetIds()
        WidgetUpdater.clearDeleted(appWidgetIds)
        if (WidgetUpdater.widgetIds(context).isEmpty()) {
            WidgetUpdater.cancelFallback(context)
            WidgetPollingService.stop(context)
        }
        super.onDeleted(context, appWidgetIds)
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        try {
            if (WidgetRunState.isPaused(context)) return
            WidgetUpdater.invalidateWidgetIds()
            WidgetUpdater.scheduleFallback(context)
            WidgetUpdater.refresh(context, force = true)
            WidgetPollingService.startIfNeeded(context)
        } catch (error: RuntimeException) {
            Log.e("ChargeWattStartup", "Widget update failed", error)
        }
    }
}

/** Fallback work is only needed when neither the Activity nor widget service is sampling. */
internal object WidgetSamplingPolicy {
    fun shouldSample(paused: Boolean, widgetsInstalled: Boolean,
                     activityResumed: Boolean, serviceRunning: Boolean): Boolean =
        !paused && widgetsInstalled && !activityResumed && !serviceRunning
}

class ChargeWattWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        val app = applicationContext
        if (WidgetSamplingPolicy.shouldSample(
                paused = WidgetRunState.isPaused(app),
                widgetsInstalled = WidgetUpdater.widgetIds(app).isNotEmpty(),
                activityResumed = WidgetPollingActivityState.resumed,
                serviceRunning = WidgetPollingActivityState.serviceRunning)) {
            WidgetUpdater.refresh(app)
        }
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

/** Keep 4-digit mA values readable in narrow 1x1 Pixel launcher cells. */
enum class WidgetChargeIcon { AC, WIRELESS, DISCHARGING, FULL, IDLE }

object WidgetTextPolicy {
    fun current(currentMa: Double?): String {
        if (currentMa == null || !currentMa.isFinite()) return "—mA"
        val magnitude = kotlin.math.abs(currentMa)
        val sign = if (currentMa > 0.0) "+" else if (currentMa < 0.0) "−" else ""
        val pattern = when {
            magnitude >= 10_000.0 -> "%.1fA"
            magnitude >= 1000.0 -> "%.2fA"
            else -> "%.0fmA"
        }
        val amount = if (magnitude >= 1000.0) magnitude / 1000.0 else magnitude
        return sign + String.format(java.util.Locale.US, pattern, amount)
    }
    fun power(powerW: Double?): String {
        if (powerW == null || !powerW.isFinite()) return "—W"
        val magnitude = kotlin.math.abs(powerW)
        val sign = if (powerW > 0) "+" else if (powerW < 0) "−" else ""
        val pattern = if (magnitude >= 100.0) "%.0fW" else if (magnitude >= 10.0) "%.1fW" else "%.2fW"
        return sign + String.format(java.util.Locale.US, pattern, magnitude)
    }
    fun voltage(voltageV: Double?): String =
        if (voltageV == null || !voltageV.isFinite()) "—V"
        else String.format(java.util.Locale.US, "%.2fV", voltageV)


    fun temperature(reading: BatteryReading): String = reading.temperatureC
        ?.let { String.format(java.util.Locale.US, "%02d°", it.roundToInt()) } ?: "—°"
    fun level(reading: BatteryReading): String = reading.level?.coerceIn(0, 100)?.let { "$it%" } ?: "—%"
    fun chargeIcon(reading: BatteryReading): WidgetChargeIcon = when (reading.mode) {
        ChargeMode.DISCHARGING -> WidgetChargeIcon.DISCHARGING
        ChargeMode.FULL -> WidgetChargeIcon.FULL
        ChargeMode.CHARGING -> if (reading.source == PowerSource.WIRELESS) WidgetChargeIcon.WIRELESS else WidgetChargeIcon.AC
        ChargeMode.IDLE -> WidgetChargeIcon.IDLE
    }

    fun primary(reading: BatteryReading, unit: String): String =
        if (unit == "mA") current(reading.currentMa) else power(reading.powerW)
    fun secondary(reading: BatteryReading, unit: String): String =
        if (unit == "mA") power(reading.powerW) else current(reading.currentMa)
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
        if (ids.isEmpty()) return // No installed widget: no battery-system call.
        val value = reading ?: BatterySampler.read(context, freshSnapshot = force)
        val unit = ChargeWattSettings.unit(context)
        if (ids.isEmpty()) return
        val theme = ChargeWattSettings.theme(context)
        val showBorder = ChargeWattSettings.showBorder(context)
        val styleKey = "$theme|border=$showBorder"
        val fingerprint = value.fingerprint(unit, styleKey)
        val now = SystemClock.elapsedRealtime()
        val full = mutableListOf<Int>()
        val partial = mutableListOf<Int>()
        for (id in ids) {
            if (!force && fingerprint == fingerprints[id]) continue
            if (WidgetUpdatePolicy.needsFull(force, themes[id] != styleKey, lastFull[id], now)) {
                full.add(id)
                lastFull[id] = now
            } else {
                partial.add(id)
            }
            fingerprints[id] = fingerprint
            themes[id] = styleKey
        }
        if (full.isEmpty() && partial.isEmpty()) return
        val layout = when (theme) {
            "transparent" -> R.layout.widget_transparent
            "compact" -> R.layout.widget_compact
            else -> R.layout.widget_dark
        }
        val views = RemoteViews(context.packageName, layout)
        views.setTextViewText(R.id.widget_value, WidgetTextPolicy.primary(value, unit))
        views.setTextViewText(R.id.widget_sub, WidgetTextPolicy.secondary(value, unit))
        views.setTextViewText(R.id.widget_voltage, WidgetTextPolicy.voltage(value.voltageV))
        views.setTextViewText(R.id.widget_temperature, WidgetTextPolicy.temperature(value))
        views.setTextViewText(R.id.widget_level, WidgetTextPolicy.level(value))
        val icon = when (WidgetTextPolicy.chargeIcon(value)) {
            WidgetChargeIcon.AC -> R.drawable.ic_widget_ac
            WidgetChargeIcon.WIRELESS -> R.drawable.ic_widget_wireless
            WidgetChargeIcon.DISCHARGING -> R.drawable.ic_widget_discharging
            WidgetChargeIcon.FULL -> R.drawable.ic_widget_full
            WidgetChargeIcon.IDLE -> R.drawable.ic_widget_idle
        }
        views.setImageViewResource(R.id.widget_charge_icon, icon)
        views.setContentDescription(R.id.widget_charge_icon, when (WidgetTextPolicy.chargeIcon(value)) {
            WidgetChargeIcon.AC -> "AC charging"
            WidgetChargeIcon.WIRELESS -> "Wireless Qi charging"
            WidgetChargeIcon.DISCHARGING -> "Discharging"
            WidgetChargeIcon.FULL -> "Battery full"
            WidgetChargeIcon.IDLE -> "Not charging"
        })
        val background = when {
            showBorder && theme == "transparent" -> R.drawable.widget_bg_transparent
            !showBorder && theme == "transparent" -> R.drawable.widget_bg_transparent_borderless
            showBorder && theme == "compact" -> R.drawable.widget_bg_compact
            !showBorder && theme == "compact" -> R.drawable.widget_bg_compact_borderless
            showBorder -> R.drawable.widget_bg_dark
            else -> R.drawable.widget_bg_dark_borderless
        }
        views.setInt(R.id.widget_root, "setBackgroundResource", background)
        val draining = when {
            value.currentMa != null -> value.currentMa < 0.0
            else -> value.mode == ChargeMode.DISCHARGING
        }
        // Contrast follows measured net battery flow, independent of plug type.
        // XML owns the glass background and icon assets; no bitmap composition.
        views.setTextColor(R.id.widget_value,
            context.getColor(if (draining) R.color.chargewatt_discharging else R.color.chargewatt_charging))
        views.setContentDescription(
            R.id.widget_root,
            "${value.statusText}, ${WidgetTextPolicy.temperature(value)}, ${WidgetTextPolicy.level(value)}, " +
                "${value.signedWatts}, ${value.signedCurrent}, ${value.voltageText}"
        )
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
