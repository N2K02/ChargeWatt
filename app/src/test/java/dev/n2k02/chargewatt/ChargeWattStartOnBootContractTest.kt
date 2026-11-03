package dev.n2k02.chargewatt

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeWattStartOnBootContractTest {
    private fun source(path: String): String = listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun bootRestoreDefaultsEnabledAndOnlyStartsForInstalledWidgets() {
        val settings = source("app/src/main/java/dev/n2k02/chargewatt/BatteryReading.kt")
        val receiver = source("app/src/main/java/dev/n2k02/chargewatt/ChargeWattBootReceiver.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")
        val activity = source("app/src/main/java/dev/n2k02/chargewatt/MainActivity.kt")
        assertTrue(settings.contains("getBoolean(KEY_START_ON_BOOT, true)"))
        assertTrue(settings.contains("putBoolean(KEY_START_ON_BOOT, value)"))
        assertTrue(receiver.contains("if (!ChargeWattSettings.startOnBoot(context)) return"))
        assertTrue(receiver.contains("WidgetUpdater.widgetIds(context).isEmpty()"))
        assertTrue(receiver.contains("WidgetPollingService.startIfNeeded(context)"))
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(activity.contains("\"Startup\" ->"))
    }
}
