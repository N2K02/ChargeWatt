package dev.n2k02.chargewatt

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Regression guards for notification-free floating battery monitoring. */
class ChargeOverlayContractTest {
    private fun file(path: String): String {
        val options = listOf(File("src/main/$path"), File("app/src/main/$path"))
        return options.first { it.isFile }.readText()
    }

    @Test fun overlayIsOptionalNotificationFreeAndSharesExistingSample() {
        val overlay = file("java/dev/n2k02/chargewatt/ChargeOverlay.kt")
        val manifest = file("AndroidManifest.xml")
        val polling = file("java/dev/n2k02/chargewatt/WidgetPollingService.kt")
        val widget = file("java/dev/n2k02/chargewatt/WidgetReceiver.kt")
        assertTrue(manifest.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertTrue(overlay.contains("TYPE_APPLICATION_OVERLAY"))
        assertTrue(overlay.contains("Settings.canDrawOverlays"))
        assertTrue(overlay.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"))
        assertFalse(overlay.contains("startForeground("))
        assertFalse(overlay.contains("NotificationManager"))
        assertFalse(overlay.contains("delay("))
        assertFalse(overlay.contains("BatterySampler.read("))
        assertTrue(widget.contains("ChargeOverlay.render(value, unit)"))
        assertTrue(polling.contains("ChargeOverlay.start(this)"))
        assertTrue(polling.contains("ChargeOverlay.stop()"))
        assertTrue(polling.contains("scope.cancel()"))
        assertFalse(manifest.contains("POST_NOTIFICATIONS"))
        assertFalse(manifest.contains("FOREGROUND_SERVICE"))
    }

    @Test fun permissionGrantAndQuitControlOverlayLifetime() {
        val ui = file("java/dev/n2k02/chargewatt/MainActivity.kt")
        val settings = file("java/dev/n2k02/chargewatt/BatteryReading.kt")
        assertTrue(ui.contains("Floating meter"))
        assertTrue(ui.contains("ACTION_MANAGE_OVERLAY_PERMISSION"))
        assertTrue(ui.contains("ChargeWattSettings.setOverlayEnabled"))
        assertTrue(ui.contains("ChargeOverlay.stop()"))
        assertTrue(ui.contains("WidgetPollingService.stop(this)"))
        assertTrue(ui.contains("WidgetRunState.setPaused(this, true)"))
        assertTrue(settings.contains("floating_overlay_enabled"))
        assertTrue(settings.contains("getBoolean(KEY_OVERLAY, false)"))
    }

    @Test fun usbTransitionsAlwaysSampleFreshBatteryState() {
        val battery = file("java/dev/n2k02/chargewatt/BatteryReading.kt")
        val widget = file("java/dev/n2k02/chargewatt/WidgetReceiver.kt")
        val receiver = file("java/dev/n2k02/chargewatt/PowerConnectionReceiver.kt")
        assertTrue(battery.contains("freshSnapshot: Boolean = false"))
        assertTrue(battery.contains("if (freshSnapshot)"))
        assertTrue(widget.contains("freshSnapshot = force"))
        assertTrue(receiver.contains("Intent.ACTION_POWER_CONNECTED") ||
            file("AndroidManifest.xml").contains("ACTION_POWER_CONNECTED"))
        assertTrue(receiver.contains("BatterySampler.clear()"))
        assertTrue(receiver.contains("Thread.sleep(1_500L)"))
    }

    @Test fun bothWidgetThemesAndRuntimeUseGreenQiBadgeText() {
        for (theme in listOf("dark", "transparent")) {
            val file = listOf(
                File("src/main/res/layout/widget_$theme.xml"),
                File("app/src/main/res/layout/widget_$theme.xml")
            ).first { it.isFile }
            val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = xml.getElementsByTagName("TextView")
            val badge = (0 until nodes.length).map { nodes.item(it) }
                .first { it.attributes.getNamedItem("android:id")?.nodeValue?.endsWith("/widget_status") == true }
            assertEquals("#5BF19C", badge.attributes.getNamedItem("android:textColor")?.nodeValue)
        }
        val widget = file("java/dev/n2k02/chargewatt/WidgetReceiver.kt")
        assertTrue(widget.contains("views.setTextColor(R.id.widget_status"))
        assertTrue(widget.contains("context.getColor(R.color.chargewatt_charging)"))
        assertFalse(widget.contains("value.source == PowerSource.WIRELESS) R.color.chargewatt_purple"))
    }
}
