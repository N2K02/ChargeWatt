package dev.n2k02.chargewatt

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.Locale
import kotlin.math.abs

enum class ChargeMode { CHARGING, DISCHARGING, FULL, IDLE }
enum class PowerSource { BATTERY, AC, USB, WIRELESS, UNKNOWN }

data class BatteryReading(
    val level: Int?,
    val currentMa: Double?,
    val voltageV: Double?,
    val powerW: Double?,
    val temperatureC: Double?,
    val mode: ChargeMode,
    val source: PowerSource,
    val health: String,
    val cycleCount: Int?,
    val sampledAtMs: Long,
) {
    val signedWatts: String get() = BatteryMath.formatSigned(powerW, "W", 2)
    val signedCurrent: String get() = BatteryMath.formatSigned(currentMa, "mA", 0)
    val voltageText: String get() = voltageV?.let { "%.2f V".format(Locale.US, it) } ?: "— V"
    val tempText: String get() = temperatureC?.let { "%.1f °C".format(Locale.US, it) } ?: "— °C"
    val levelText: String get() = level?.let { "$it%" } ?: "—%"
    val powerSourceText: String get() = when (source) {
        PowerSource.BATTERY -> "Battery"
        PowerSource.AC -> "AC charger"
        PowerSource.USB -> "USB charger"
        PowerSource.WIRELESS -> "Wireless Qi"
        PowerSource.UNKNOWN -> "Unknown charger"
    }
    val statusText: String get() = when {
        source == PowerSource.WIRELESS && (
            (currentMa != null && currentMa < 0.0) ||
                (currentMa == null && mode == ChargeMode.DISCHARGING)
        ) -> "Wireless Qi · net battery drain"
        source == PowerSource.WIRELESS && mode == ChargeMode.FULL -> "Wireless Qi · Full"
        source == PowerSource.WIRELESS -> "Wireless Qi"
        mode == ChargeMode.CHARGING && (currentMa ?: 0.0) < 0.0 -> "Plugged in · net drain"
        mode == ChargeMode.DISCHARGING -> "Discharging"
        mode == ChargeMode.FULL -> "Full"
        mode == ChargeMode.IDLE -> if (source == PowerSource.UNKNOWN) "State unknown" else "Not charging"
        mode == ChargeMode.CHARGING -> when (source) {

            PowerSource.WIRELESS -> "Wireless Qi"
            PowerSource.AC -> "AC charging"
            PowerSource.USB -> "USB charging"
            PowerSource.BATTERY -> "Charging"
            PowerSource.UNKNOWN -> "Charging"
        }
        else -> "Unknown"
    }
    fun sameContent(other: BatteryReading): Boolean =
        copy(sampledAtMs = 0L) == other.copy(sampledAtMs = 0L)
    fun fingerprint(unit: String, theme: String): String =
        listOf(mode, source, signedWatts, signedCurrent, voltageText, theme, unit).joinToString("|")
}

object BatteryMath {
    const val DEFAULT_REFRESH_SECONDS = 1
    fun refreshSeconds(requested: Int): Int = requested.coerceIn(1, 10)
    fun watts(currentMicroAmps: Int?, voltageMilliVolts: Int?): Double? {
        if (currentMicroAmps == null || voltageMilliVolts == null) return null
        if (currentMicroAmps == Int.MIN_VALUE || voltageMilliVolts <= 0) return null
        if (abs(currentMicroAmps.toLong()) > 20_000_000L) return null
        return currentMicroAmps.toDouble() * voltageMilliVolts / 1_000_000_000.0
    }
    fun formatSigned(value: Double?, unit: String, digits: Int): String {
        if (value == null || !value.isFinite()) return "— $unit"
        val number = "%."+digits+"f"
        val magnitude = String.format(Locale.US, number, abs(value))
        return (if (value > 0) "+" else if (value < 0) "−" else "") + magnitude + " " + unit
    }
    // EXTRA_PLUGGED can contain multiple flags on some device implementations.
    // Prefer wireless for a Qi pad, then USB, then AC.
    fun source(plugged: Int): PowerSource = when {
        plugged == 0 -> PowerSource.BATTERY
        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> PowerSource.WIRELESS
        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> PowerSource.USB
        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> PowerSource.AC
        else -> PowerSource.UNKNOWN
    }
    fun mode(status: Int, plugged: Int): ChargeMode = when (status) {
        BatteryManager.BATTERY_STATUS_FULL -> ChargeMode.FULL
        BatteryManager.BATTERY_STATUS_CHARGING -> ChargeMode.CHARGING
        BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeMode.DISCHARGING
        else -> ChargeMode.IDLE
    }
}

object ChargeWattSettings {
    private const val FILE = "chargewatt"
    private const val KEY_INTERVAL = "refresh_seconds"
    private const val KEY_THEME = "widget_theme"
    private const val KEY_UNIT = "primary_unit"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun interval(context: Context): Int =
        BatteryMath.refreshSeconds(prefs(context).getInt(KEY_INTERVAL, BatteryMath.DEFAULT_REFRESH_SECONDS))
    fun setInterval(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL, BatteryMath.refreshSeconds(value)).apply()
    }
    fun theme(context: Context): String = prefs(context).getString(KEY_THEME, "dark") ?: "dark"
    fun setTheme(context: Context, value: String) {
        prefs(context).edit().putString(KEY_THEME, if (value == "transparent") value else "dark").apply()
    }
    fun removeLegacyEvents(context: Context) {
        val settings = prefs(context)
        if (!settings.getBoolean("legacy_events_deleted_v1", false)) {
            context.deleteSharedPreferences("chargewatt_events")
            settings.edit().putBoolean("legacy_events_deleted_v1", true).apply()
        }
    }
    fun unit(context: Context): String = prefs(context).getString(KEY_UNIT, "W") ?: "W"
    fun setUnit(context: Context, value: String) {
        prefs(context).edit().putString(KEY_UNIT, if (value == "mA") value else "W").apply()
    }
}

object BatterySampler {
    // The resumed activity caches ACTION_BATTERY_CHANGED, avoiding 1Hz sticky
    // broadcast reads. Workers use a single sticky query when not in foreground.
    @Volatile private var latestIntent: Intent? = null
    fun remember(intent: Intent?) {
        if (intent?.action == Intent.ACTION_BATTERY_CHANGED) latestIntent = intent
    }
    fun clear() { latestIntent = null }
    fun read(context: Context): BatteryReading {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = latestIntent ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 0) ?: 0
        val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val level = if (rawLevel >= 0 && scale > 0) (rawLevel * 100 / scale).coerceIn(0, 100) else null
        val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }
        val current = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeUnless { it == Int.MIN_VALUE || abs(it.toLong()) > 20_000_000L }
        val ma = current?.div(1000.0)
        val c = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }?.div(10.0)
        val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheated"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Overvoltage"
            else -> "Unknown"
        }
        val cycles = intent?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 }
        return BatteryReading(
            level = level,
            currentMa = ma,
            voltageV = mv?.div(1000.0),
            powerW = BatteryMath.watts(current, mv),
            temperatureC = c,
            mode = BatteryMath.mode(status, plugged),
            source = BatteryMath.source(plugged),
            health = health,
            cycleCount = cycles,
            sampledAtMs = System.currentTimeMillis(),
        )
    }
}
