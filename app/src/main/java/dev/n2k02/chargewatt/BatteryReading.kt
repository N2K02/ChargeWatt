package dev.n2k02.chargewatt

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.Locale
import kotlin.math.abs

enum class ChargeMode { CHARGING, DISCHARGING, FULL, IDLE }
enum class PowerSource { AC, USB, WIRELESS, UNKNOWN }

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
    val signedWatts: String get() = when {
        powerW == null -> "— W"
        mode == ChargeMode.CHARGING -> "+%.2f W".format(Locale.US, powerW)
        mode == ChargeMode.DISCHARGING -> "−%.2f W".format(Locale.US, powerW)
        else -> "%.2f W".format(Locale.US, powerW)
    }
    val signedCurrent: String get() = when {
        currentMa == null -> "— mA"
        mode == ChargeMode.CHARGING -> "+%.0f mA".format(Locale.US, currentMa)
        mode == ChargeMode.DISCHARGING -> "−%.0f mA".format(Locale.US, currentMa)
        else -> "%.0f mA".format(Locale.US, currentMa)
    }
    val voltageText: String get() = voltageV?.let { "%.2f V".format(Locale.US, it) } ?: "— V"
    val tempText: String get() = temperatureC?.let { "%.1f °C".format(Locale.US, it) } ?: "— °C"
    val levelText: String get() = level?.let { "$it%" } ?: "—%"
    val statusText: String get() = when (mode) {
        ChargeMode.DISCHARGING -> "Discharging"
        ChargeMode.FULL -> "Full"
        ChargeMode.IDLE -> "Not charging"
        ChargeMode.CHARGING -> when (source) {
            PowerSource.WIRELESS -> "Wireless Qi"
            PowerSource.AC -> "AC charging"
            PowerSource.USB -> "USB charging"
            PowerSource.UNKNOWN -> "Charging"
        }
    }
    fun fingerprint(unit: String, theme: String): String =
        listOf(mode, source, levelText, signedWatts, signedCurrent, voltageText, theme, unit).joinToString("|")
}

object BatteryMath {
    const val DEFAULT_REFRESH_SECONDS = 1
    fun refreshSeconds(requested: Int): Int = requested.coerceIn(1, 10)
    fun watts(currentMicroAmps: Int?, voltageMilliVolts: Int?): Double? {
        if (currentMicroAmps == null || voltageMilliVolts == null) return null
        if (currentMicroAmps == Int.MIN_VALUE || voltageMilliVolts <= 0) return null
        if (abs(currentMicroAmps.toLong()) > 20_000_000L) return null
        return abs(currentMicroAmps.toDouble()) * voltageMilliVolts / 1_000_000_000.0
    }
    fun source(plugged: Int): PowerSource = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> PowerSource.AC
        BatteryManager.BATTERY_PLUGGED_USB -> PowerSource.USB
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> PowerSource.WIRELESS
        else -> PowerSource.UNKNOWN
    }
    fun mode(status: Int, plugged: Int): ChargeMode = when {
        status == BatteryManager.BATTERY_STATUS_FULL -> ChargeMode.FULL
        status == BatteryManager.BATTERY_STATUS_CHARGING -> ChargeMode.CHARGING
        plugged != 0 -> ChargeMode.IDLE
        else -> ChargeMode.DISCHARGING
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
    fun unit(context: Context): String = prefs(context).getString(KEY_UNIT, "W") ?: "W"
    fun setUnit(context: Context, value: String) {
        prefs(context).edit().putString(KEY_UNIT, if (value == "mA") value else "W").apply()
    }
}

object BatterySampler {
    fun read(context: Context): BatteryReading {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 0) ?: 0
        val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val level = if (rawLevel >= 0 && scale > 0) (rawLevel * 100 / scale).coerceIn(0, 100) else null
        val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }
        val current = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeUnless { it == Int.MIN_VALUE || abs(it.toLong()) > 20_000_000L }
        val ma = current?.let { abs(it.toDouble()) / 1000.0 }
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
