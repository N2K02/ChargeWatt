package dev.n2k02.chargewatt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import dev.n2k02.theme.N2K02Theme
import dev.n2k02.updater.N2K02UpdateBadge
import dev.n2k02.updater.N2K02UpdateBadgedIcon
import dev.n2k02.updater.N2K02UpdateScreen
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
    override fun onResume() {
        super.onResume()
        ChargeWattUpdater.onResume(this)
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
        enableEdgeToEdge()
        ChargeWattSettings.removeLegacyEvents(this)
        ChargeWattUpdater.init(this)
        Log.i(TAG, "onCreate: restoring dashboard")
        // WorkManager is registered when the widget is enabled/onUpdate. There
        // is no reason to touch OS scheduling on an ordinary app launch.
        WidgetRunState.setPaused(this, false)
        try {
            setContent {
              N2K02Theme {
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
                    ChargeWattUpdater.controller.cancel()
                    finishAndRemoveTask()
                },
            )
              }
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
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var page by rememberSaveable { mutableStateOf("Home") }
    BackHandler(enabled = page != "Home") {
        page = if (page == "Settings") "Home" else "Settings"
    }
    var interval by remember { mutableIntStateOf(ChargeWattSettings.interval(context)) }
    var theme by remember { mutableStateOf(ChargeWattSettings.theme(context)) }
    var unit by remember { mutableStateOf(ChargeWattSettings.unit(context)) }
    val updaterStatus by ChargeWattUpdater.controller.status.collectAsState()
    val experimentalEnabled by ChargeWattUpdater.experimentalEnabled.collectAsState()
    val accent = if (reading?.currentMa?.let { it < 0 } == true ||
        (reading?.currentMa == null && reading?.mode == ChargeMode.DISCHARGING)) Amber else Green
    // Same shared Material 3 Expressive theme as mRadio (setContent wrapper).
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                // Single app-owned header on every screen, exactly like mRadio.
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier.fillMaxWidth().statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                when (page) {
                                    "Settings" -> Icons.Default.Settings
                                    "Widget" -> Icons.Default.Tune
                                    "Details" -> Icons.Default.Info
                                    "Updates" -> Icons.Default.SystemUpdate
                                    else -> Icons.Default.Home
                                },
                                contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                when (page) {
                                    "Widget" -> "Widget display"
                                    "Details" -> "Battery details"
                                    "Updates" -> "About & updates"
                                    else -> page
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AndroidView(
                                factory = { ctx ->
                                    android.widget.ImageView(ctx).apply {
                                        setImageDrawable(ctx.packageManager.getApplicationIcon(ctx.packageName))
                                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                    }
                                },
                                modifier = Modifier.size(28.dp)
                            )
                            Text("chargeWatt", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.navigationBarsPadding()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBarItem(
                        selected = page == "Home",
                        onClick = { page = "Home" },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = page != "Home",
                        onClick = { page = "Settings" },
                        icon = {
                            N2K02UpdateBadgedIcon(updaterStatus) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        },
                        label = { Text("Settings") }
                    )
                    NavigationBarItem(
                        selected = false, onClick = onQuit,
                        icon = { Icon(Icons.Default.ExitToApp, contentDescription = null) },
                        label = { Text("Quit") }
                    )
                        }
                    }
                }
            },
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
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
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
                            ValueTile("Power source", reading?.powerSourceText ?: "Unavailable", Modifier.weight(1f))
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
                        Text(
                            "Widget and battery settings",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 12.dp)
                        )
                        SettingsMenuRow(
                            "Widget display", "Theme, power units and refresh interval",
                            Icons.Default.Tune
                        ) { page = "Widget" }
                        SettingsMenuRow(
                            "Battery details", "Current, voltage, health and cycle count",
                            Icons.Default.Info
                        ) { page = "Details" }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        SettingsMenuRow(
                            "About & updates", "App version and update checks",
                            Icons.Default.SystemUpdate,
                            trailing = { N2K02UpdateBadge(updaterStatus) },
                        ) { page = "Updates" }
                    }
                    "Widget" -> {
                        SectionLabel("Widget • 1×1")
                        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
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
                    }
                    "Details" -> {
                        DetailRow("State", reading?.statusText ?: "Unknown")
                        DetailRow("Battery current", reading?.signedCurrent ?: "— mA")
                        DetailRow("Battery-side power", reading?.signedWatts ?: "— W")
                        DetailRow("Voltage", reading?.voltageText ?: "— V")
                        DetailRow("Temperature", reading?.tempText ?: "— °C")
                        DetailRow("Battery level", reading?.levelText ?: "—%")
                        DetailRow("Power source", reading?.powerSourceText ?: "Unavailable")
                        DetailRow("Health", reading?.health ?: "Unknown")
                        DetailRow("Cycle count", reading?.cycleCount?.toString() ?: "Not available")
                        Text(
                            "Device-reported battery-side power, not wall-adapter input power.",
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    "Updates" -> {
                        N2K02UpdateScreen(
                            controller = ChargeWattUpdater.controller,
                            installGuard = ChargeWattUpdater.installGuard,
                            experimentalEnabled = experimentalEnabled,
                            onExperimentalEnabledChange = ChargeWattUpdater::setExperimentalEnabled,
                        )
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
@Composable
private fun SettingsMenuRow(
    title: String, subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    trailing: @Composable (() -> Unit)? = null,
    action: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.fillMaxWidth().clickable(onClick = action)
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
    )
}
