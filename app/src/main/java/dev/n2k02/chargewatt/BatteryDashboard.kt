package dev.n2k02.chargewatt

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Recycling
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/** Static drawing only: no per-frame animations or additional battery reads. */
internal object ChargeWattPalette {
    val Charging = Color(0xFF5BF19C)
    val Discharging = Color(0xFFFFB443)
    val Voltage = Color(0xFF9BD6DE)
    val Purple = Color(0xFFC7A9FC)
    val Glass = Color(0xFF1C2629)
    val Outline = Color(0xFF667F86)
}

private fun isDraining(reading: BatteryReading?): Boolean =
    reading?.currentMa?.let { it < 0.0 }
        ?: (reading?.mode == ChargeMode.DISCHARGING)

private fun statusColor(reading: BatteryReading?) =
    if (isDraining(reading)) ChargeWattPalette.Discharging else ChargeWattPalette.Charging

/**
 * Same visual hierarchy on Home and Details. The hero contains no repeated
 * app header: the parent mRadio-style Scaffold owns that header.
 */
@Composable
internal fun ChargeWattHero(reading: BatteryReading?, unit: String, modifier: Modifier = Modifier) {
    val level = (reading?.level ?: 0).coerceIn(0, 100)
    val status = reading?.statusText ?: "Reading battery…"
    val primary = if (unit == "mA") reading?.signedCurrent ?: "— mA"
        else reading?.signedWatts ?: "— W"
    val secondary = if (unit == "mA") reading?.signedWatts ?: "— W"
        else reading?.signedCurrent ?: "— mA"
    val accent = statusColor(reading)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, ChargeWattPalette.Outline.copy(alpha = .42f))
    ) {
        Box(
            modifier = Modifier.background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        ChargeWattPalette.Glass.copy(alpha = .94f),
                        MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            )
        ) {
            // Muted, static bottom wave echoes the widget's glass finish.
            Canvas(Modifier.matchParentSize()) {
                val wave = Path().apply {
                    moveTo(0f, size.height * .85f)
                    cubicTo(
                        size.width * .27f, size.height * .73f,
                        size.width * .48f, size.height * 1.01f,
                        size.width, size.height * .78f
                    )
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(
                    wave,
                    brush = Brush.horizontalGradient(
                        listOf(
                            ChargeWattPalette.Charging.copy(alpha = .19f),
                            ChargeWattPalette.Voltage.copy(alpha = .11f),
                            ChargeWattPalette.Charging.copy(alpha = .24f)
                        )
                    )
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 27.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(118.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { level / 100f },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 10.dp,
                        color = ChargeWattPalette.Charging,
                        trackColor = ChargeWattPalette.Outline.copy(alpha = .32f)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.BatteryFull, contentDescription = null,
                            tint = ChargeWattPalette.Charging, modifier = Modifier.size(23.dp))
                        Text(
                            reading?.levelText ?: "—%",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = accent.copy(alpha = .16f),
                        border = BorderStroke(1.dp, accent.copy(alpha = .38f))
                    ) {
                        Row(
                            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Bolt, contentDescription = null,
                                tint = accent, modifier = Modifier.size(15.dp))
                            Text(status, color = accent,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                lineHeight = 14.sp)
                        }
                    }
                    Text(
                        primary, style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = accent,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(secondary, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                    Text(reading?.voltageText ?: "— V",
                        style = MaterialTheme.typography.titleMedium,
                        color = ChargeWattPalette.Voltage)
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String, value: String, icon: ImageVector,
    accent: Color, modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, accent.copy(alpha = .25f)),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(shape = CircleShape, color = accent.copy(alpha = .15f)) {
                Icon(icon, contentDescription = null,
                    modifier = Modifier.padding(7.dp).size(19.dp), tint = accent)
            }
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun SectionTitle(title: String, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = .16f)) {
            Icon(icon, contentDescription = null, tint = color,
                modifier = Modifier.padding(8.dp).size(21.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun HomeDashboard(
    reading: BatteryReading?, unit: String, interval: Int, onDetails: () -> Unit
) {
    ChargeWattHero(reading, unit)
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard("Temperature", reading?.tempText ?: "— °C",
            Icons.Rounded.Thermostat, ChargeWattPalette.Purple, Modifier.weight(1f))
        MetricCard("Health", reading?.health ?: "Unknown",
            Icons.Rounded.FavoriteBorder, ChargeWattPalette.Charging, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard("Power source", reading?.powerSourceText ?: "Unavailable",
            Icons.Rounded.Power, ChargeWattPalette.Voltage, Modifier.weight(1f))
        MetricCard("Cycle count", reading?.cycleCount?.toString() ?: "Unavailable",
            Icons.Rounded.Recycling, ChargeWattPalette.Purple, Modifier.weight(1f))
    }
    Spacer(Modifier.height(4.dp))
    Surface(
        onClick = onDetails,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, ChargeWattPalette.Voltage.copy(alpha = .28f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Rounded.Info, contentDescription = null,
                tint = ChargeWattPalette.Voltage, modifier = Modifier.size(23.dp))
            Text("Battery details", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Open battery details")
        }
    }
    Text(
        "Foreground refresh every " + interval +
            "s. Background updates are system-managed; no notification or foreground service.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String, icon: ImageVector, accent: Color) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(21.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(50), color = accent.copy(alpha = .14f)) {
            Text(value, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
        }
    }
}

@Composable
internal fun BatteryDetailsDashboard(reading: BatteryReading?, unit: String) {
    ChargeWattHero(reading, unit)
    Spacer(Modifier.height(8.dp))
    SectionTitle("Electrical", Icons.Rounded.Bolt, ChargeWattPalette.Discharging)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard("Battery current", reading?.signedCurrent ?: "— mA",
            Icons.Rounded.ShowChart, ChargeWattPalette.Purple, Modifier.weight(1f))
        MetricCard("Battery-side power", reading?.signedWatts ?: "— W",
            Icons.Rounded.Bolt, statusColor(reading), Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard("Voltage", reading?.voltageText ?: "— V",
            Icons.Rounded.WaterDrop, ChargeWattPalette.Voltage, Modifier.weight(1f))
        MetricCard("Temperature", reading?.tempText ?: "— °C",
            Icons.Rounded.Thermostat, ChargeWattPalette.Purple, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    SectionTitle("Battery information", Icons.Rounded.BatteryFull, ChargeWattPalette.Purple)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(23.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, ChargeWattPalette.Outline.copy(alpha = .33f))
    ) {
        Column {
            InfoRow("Battery level", reading?.levelText ?: "—%",
                Icons.Rounded.BatteryFull, ChargeWattPalette.Charging)
            HorizontalDividerForBattery()
            InfoRow("Power source", reading?.powerSourceText ?: "Unavailable",
                Icons.Rounded.Power, ChargeWattPalette.Voltage)
            HorizontalDividerForBattery()
            InfoRow("Health", reading?.health ?: "Unknown",
                Icons.Rounded.FavoriteBorder, ChargeWattPalette.Charging)
            HorizontalDividerForBattery()
            InfoRow("Cycle count", reading?.cycleCount?.toString() ?: "Unavailable",
                Icons.Rounded.Recycling, ChargeWattPalette.Purple)
        }
    }
    Text(
        "Device-reported battery-side measurements; not charger or wall-adapter input power.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 5.dp, vertical = 6.dp)
    )
}

@Composable
private fun HorizontalDividerForBattery() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 47.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .42f)
    )
}
