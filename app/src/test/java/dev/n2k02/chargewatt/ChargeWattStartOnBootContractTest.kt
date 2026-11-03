package dev.n2k02.chargewatt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChargeWattStartOnBootContractTest {
    @Test fun bootRestoreDefaultsEnabledAndOnlyStartsForInstalledWidgets() {
        val root = File("src/main")
        val settings = File(root, "java/dev/n2k02/chargewatt/BatteryReading.kt").readText()
        val receiver = File(root, "java/dev/n2k02/chargewatt/ChargeWattBootReceiver.kt").readText()
        val manifest = File(root, "AndroidManifest.xml").readText()
        val activity = File(root, "java/dev/n2k02/chargewatt/MainActivity.kt").readText()
        assertTrue(settings.contains("getBoolean(KEY_START_ON_BOOT, true)"))
        assertTrue(settings.contains("putBoolean(KEY_START_ON_BOOT, value)"))
        assertTrue(receiver.contains("if (!ChargeWattSettings.startOnBoot(context)) return"))
        assertTrue(receiver.contains("WidgetUpdater.widgetIds(context).isEmpty()"))
        assertTrue(receiver.contains("WidgetPollingService.startIfNeeded(context)"))
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(activity.contains("\"Startup\" ->"))
    }
}
