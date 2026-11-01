package dev.n2k02.chargewatt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0F1415)
private val CardBg = Color(0xFF1B2426)
private val Green = Color(0xFF64EE94)
private val Amber = Color(0xFFFFB74B)

class MainActivity : ComponentActivity() {
    companion object { private const val TAG = "ChargeWattStartup" }
    private val latest = mutableStateOf<BatteryReading?>(null)
    private var batteryReceiverRegistered = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            BatterySampler.remember(intent)
        }
    }
    override fun onStart() {
        super.onStart()
        // Sticky battery broadcasts are best effort. Registration failure must
        // not terminate the Activity; the sampler can query a snapshot instead.
        try {
            val sticky = registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiverRegistered = true
            BatterySampler.remember(sticky)
            Log.i(TAG, "Battery observer registered")
        } catch (e: RuntimeException) {
            batteryReceiverRegistered = false
            Log.e(TAG, "Battery observer registration failed", e)
        }
    }
    override fun onStop() {
        if (batteryReceiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Battery observer cleanup failed", e)
            } finally {
                batteryReceiverRegistered = false
            }
        }
        BatterySampler.clear()
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: restoring dashboard")
        // WorkManager is registered when the widget is enabled/onUpdate. There
        // is no reason to touch OS scheduling on an ordinary app launch.
        WidgetRunState.setPaused(this, false)
        try {
            setContent {
            ChargeWattScreen(
                reading = latest.value,
                onRefresh = {
                    try {
                        WidgetUpdater.refresh(this, force = true)
                    } catch (e: RuntimeException) {
                        Log.e(TAG, "Manual widget refresh failed", e)
                    }
                },
                onQuit = {
                    WidgetRunState.setPaused(this, true)
                    try {
                        WidgetUpdater.cancelFallback(this)
                    } catch (e: RuntimeException) {
                        Log.e(TAG, "Widget work cancellation failed during Quit", e)
                    }
                    WidgetUpdater.clearAll()
                    finishAndRemoveTask()
                },
                openReleases = {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/N2K02/ChargeWatt/releases")))
                },
            )
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Dashboard composition startup failed", e)
            // Do not strand the user in a force-close loop if the initial
            // Compose host itself fails. A native message needs no Compose.
            setContentView(TextView(this).apply {
                text = "chargeWatt could not initialize its dashboard. Check ChargeWattStartup in Android logs."
                textSize = 17f
                setPadding(32, 56, 32, 32)
            })
            return
        }
        Log.i(TAG, "Dashboard host initialized")
        lifecycleScope.launch {
            var previousFailure: String? = null
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    try {
                    // Battery current is queried off the UI thread; battery state
                    // and voltage are cached from a single dynamic broadcast receiver.
                    val reading = withContext(Dispatchers.IO) {
                        val sample = BatterySampler.read(applicationContext)
                        // RemoteViews IPC + event-only persistence must not block
                        // Compose/main thread at the 1-second default cadence.
                        ChargingEventLog.recordIfChanged(applicationContext, sample)
                        WidgetUpdater.refresh(applicationContext, sample)
                        sample
                    }
                    if (latest.value?.sameContent(reading) != true) latest.value = reading
                    previousFailure = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // One hardware-service failure must not kill the app or
                        // produce a logcat storm every second.
                        val failure = error.javaClass.name + ":" + error.message
                        if (failure != previousFailure) {
                            Log.e(TAG, "Battery polling or widget update failed", error)
                            previousFailure = failure
                        }
                    }
                    delay(ChargeWattSettings.interval(this@MainActivity) * 1000L)
                }
            }
        }
    }
}

@Composable
private fun ChargeWattScreen(
    reading: BatteryReading?,
    onRefresh: () -> Unit,
    onQuit: () -> Unit,
    openReleases: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var page by rememberSaveable { mutableStateOf("Home") }
    var interval by remember { mutableIntStateOf(ChargeWattSettings.interval(context)) }
    var theme by remember { mutableStateOf(ChargeWattSettings.theme(context)) }
    var unit by remember { mutableStateOf(ChargeWattSettings.unit(context)) }
    val accent = if (reading?.currentMa?.let { it < 0 } == true ||
        (reading?.currentMa == null && reading?.mode == ChargeMode.DISCHARGING)) Amber else Green
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green, secondary = Amber, background = Bg, surface = CardBg,
            onBackground = Color.White, onSurface = Color.White,
        )
    ) {
        Scaffold(
            containerColor = Bg,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (page != "Home" && page != "Settings") {
                            IconButton(onClick = { page = "Settings" }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        } else {
                            Icon(if (page == "Home") Icons.Default.Home else Icons.Default.Settings, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(page, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = Green, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("chargeWatt", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = CardBg) {
                    NavigationBarItem(
                        selected = page == "Home",
                        onClick = { page = "Home" },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = page != "Home",
                        onClick = { page = "Settings" },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") }
                    )
                    NavigationBarItem(
                        selected = false, onClick = onQuit,
                        icon = { Icon(Icons.Default.ExitToApp, contentDescription = null) },
                        label = { Text("Quit") }
                    )
                }
            }
        ) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (page) {
                    "Home" -> {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = CardBg)
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(reading?.statusText ?: "Reading battery…", color = accent)
                                Text(
                                    if (unit == "mA") reading?.signedCurrent ?: "— mA" else reading?.signedWatts ?: "— W",
                                    style = MaterialTheme.typography.displayLarge,
                                    fontWeight = FontWeight.Bold, color = accent
                                )
                                Text(
                                    if (unit == "mA") reading?.signedWatts ?: "— W" else reading?.signedCurrent ?: "— mA",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text((reading?.levelText ?: "—%") + "  ·  " + (reading?.voltageText ?: "— V"),
                                    color = Color.LightGray)
                                Text("Battery-side measurements", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ValueTile("Temperature", reading?.tempText ?: "— °C", Modifier.weight(1f))
                            ValueTile("Battery", reading?.levelText ?: "—%", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ValueTile("Power source", reading?.source?.name ?: "Unknown", Modifier.weight(1f))
                            ValueTile("Health", reading?.health ?: "Unknown", Modifier.weight(1f))
                        }
                        Button(onClick = { page = "Details" }, modifier = Modifier.fillMaxWidth()) {
                            Text("Battery details")
                        }
                        Text(
                            "Foreground refresh every " + interval + "s. Android controls background updates. No notification or foreground service.",
                            style = MaterialTheme.typography.bodySmall, color = Color.LightGray
                        )
                    }
                    "Settings" -> {
                        SectionLabel("Widget • 1×1")
                        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = CardBg)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = theme == "dark", onClick = {
                                            theme = "dark"; ChargeWattSettings.setTheme(context, theme); onRefresh()
                                        }, label = { Text("Dark") }
                                    )
                                    FilterChip(
                                        selected = theme == "transparent", onClick = {
                                            theme = "transparent"; ChargeWattSettings.setTheme(context, theme); onRefresh()
                                        }, label = { Text("Transparent") }
                                    )
                                }
                                HorizontalDivider()
                                Text("Primary speed unit", style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = unit == "W", onClick = {
                                        unit = "W"; ChargeWattSettings.setUnit(context, unit); onRefresh()
                                    }, label = { Text("Watt (W)") })
                                    FilterChip(selected = unit == "mA", onClick = {
                                        unit = "mA"; ChargeWattSettings.setUnit(context, unit); onRefresh()
                                    }, label = { Text("Current (mA)") })
                                }
                                HorizontalDivider()
                                Text("Foreground refresh · " + interval + " second" + if (interval == 1) "" else "s",
                                    style = MaterialTheme.typography.titleMedium)
                                Slider(
                                    value = interval.toFloat(),
                                    onValueChange = {
                                        interval = it.toInt().coerceIn(1, 10)
                                        ChargeWattSettings.setInterval(context, interval)
                                    },
                                    valueRange = 1f..10f, steps = 8
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("1 sec", style = MaterialTheme.typography.labelSmall)
                                    Text("10 sec", style = MaterialTheme.typography.labelSmall)
                                }
                                Text(
                                    "1–10s applies while this screen is active. A widget cannot update each second reliably in the background without a foreground-running component.",
                                    style = MaterialTheme.typography.bodySmall, color = Color.LightGray
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        SectionLabel("Information")
                        SettingsItem("Battery details", "Voltage, current, temperature and health") { page = "Details" }
                        SettingsItem("Logs", "Charging changes only, daily retention") { page = "Logs" }
                        HorizontalDivider()
                        SettingsItem("Updates", "View signed stable and experimental releases") { page = "Updates" }
                    }
                    "Details" -> {
                        ValueTile("State", reading?.statusText ?: "Unknown")
                        ValueTile("Battery current", reading?.signedCurrent ?: "— mA")
                        ValueTile("Battery-side power", reading?.signedWatts ?: "— W")
                        ValueTile("Voltage", reading?.voltageText ?: "— V")
                        ValueTile("Temperature", reading?.tempText ?: "— °C")
                        ValueTile("Battery level", reading?.levelText ?: "—%")
                        ValueTile("Health", reading?.health ?: "Unknown")
                        ValueTile("Cycle count", reading?.cycleCount?.toString() ?: "Not available")
                        Text("Current is device-reported, may be averaged or unavailable. Watts = battery current × battery voltage, not adapter wattage.",
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    }
                    "Logs" -> {
                        Text("Events are recorded only when charging state or source changes, not every second.",
                            style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                        val lines = ChargingEventLog.read(context)
                        if (lines.isEmpty()) Text("No charging changes recorded yet.")
                        lines.asReversed().forEach { ValueTile("Event", it) }
                    }
                    "Updates" -> {
                        ValueTile("Channel identity", "Experimental and stable use the same package and release signature.")
                        Button(onClick = openReleases, modifier = Modifier.fillMaxWidth()) { Text("Open GitHub releases") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(value: String) {
    Text(value, color = Green, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}
@Composable
private fun ValueTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
@Composable
private fun SettingsItem(title: String, subtitle: String, action: () -> Unit) {
    Card(
        onClick = action, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        }
    }
}
