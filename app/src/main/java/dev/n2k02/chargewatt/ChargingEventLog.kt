package dev.n2k02.chargewatt

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes only power-source/state transitions, never 1-second sample data. */
object ChargingEventLog {
    private const val FILE = "chargewatt_events"
    private const val DAY = 86_400_000L
    private const val RETENTION = 7 * DAY
    fun recordIfChanged(context: Context, reading: BatteryReading, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val status = reading.mode.name + ":" + reading.source.name
        if (prefs.getString("last", null) == status) return
        val lines = prefs.getString("events", "").orEmpty().lines()
            .filter { it.isNotBlank() }.filter {
                (it.substringBefore('|').toLongOrNull() ?: 0L) >= now - RETENTION
            }.takeLast(59)
        prefs.edit().putString("last", status)
            .putString("events", (lines + (now.toString() + "|" + reading.statusText)).joinToString("\n")).apply()
    }
    fun read(context: Context): List<String> {
        val now = System.currentTimeMillis()
        val value = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("events", "").orEmpty()
        return value.lines().mapNotNull { line ->
            val at = line.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            if (at < now - RETENTION) return@mapNotNull null
            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(at)) + " · " + line.substringAfter('|')
        }
    }
}
