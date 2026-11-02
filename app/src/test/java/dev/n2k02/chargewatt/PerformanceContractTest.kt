package dev.n2k02.chargewatt

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Prevent battery/CPU regressions in future refactors. */
class PerformanceContractTest {
    private fun read(path: String): String {
        val options = listOf(File("src/main/$path"), File("app/src/main/$path"))
        val file = options.firstOrNull { it.isFile } ?: error("Cannot find source $path")
        return file.readText()
    }
    @Test fun noNotificationOrForegroundOrWakeLockPermission() {
        val manifest = read("AndroidManifest.xml")
        assertFalse(manifest.contains("POST_NOTIFICATIONS"))
        assertFalse(manifest.contains("FOREGROUND_SERVICE"))
        assertFalse(manifest.contains("WAKE_LOCK"))
        assertTrue(manifest.contains("<service android:name=\".WidgetPollingService\""))
        assertFalse(manifest.contains("foregroundServiceType"))
        assertFalse(manifest.contains("FOREGROUND_SERVICE"))
    }
    @Test fun powerEventsOnlyWakeOnConnectionChanges() {
        val manifest = read("AndroidManifest.xml")
        assertTrue(manifest.contains("ACTION_POWER_CONNECTED"))
        assertTrue(manifest.contains("ACTION_POWER_DISCONNECTED"))
        assertFalse(manifest.contains("BOOT_COMPLETED"))
    }
    @Test fun notificationFreeServiceIsBestEffortAndStopsOnQuit() {
        val service = read("java/dev/n2k02/chargewatt/WidgetPollingService.kt")
        val activity = read("java/dev/n2k02/chargewatt/MainActivity.kt")
        assertTrue(service.contains("class WidgetPollingService : Service()"))
        assertTrue(service.contains("START_STICKY"))
        assertTrue(service.contains("WidgetRunState.isPaused"))
        assertTrue(service.contains("WidgetUpdater.widgetIds"))
        assertTrue(service.contains("WidgetPollingActivityState.resumed"))
        assertTrue(service.contains("ChargeWattSettings.interval"))
        assertTrue(service.contains("scope.cancel()"))
        assertFalse(service.contains("startForeground("))
        assertFalse(service.contains("NotificationManager"))
        assertTrue(activity.contains("WidgetPollingService.startWhileVisible(this)"))
        assertTrue(activity.contains("WidgetPollingService.stop(this)"))
        assertTrue(activity.contains("WidgetPollingActivityState.resumed = false"))
    }
    @Test fun widgetCodeAvoidsOneHertzDiskWritesAndAlwaysOnJobs() {
        val widget = read("java/dev/n2k02/chargewatt/WidgetReceiver.kt")
        assertFalse(widget.contains("widget_cache"))
        assertTrue(widget.contains("mutableMapOf<Int, String>()"))
        assertTrue(widget.contains("partiallyUpdateAppWidget"))
        assertTrue(widget.contains("15, TimeUnit.MINUTES"))
        assertTrue(widget.contains("if (WidgetRunState.isPaused(context)) return"))
        assertTrue(widget.contains("if (ids.isEmpty()) return"))
    }
    @Test fun samplingAndRenderingRespectActivityLifecycle() {
        val activity = read("java/dev/n2k02/chargewatt/MainActivity.kt")
        assertTrue(activity.contains("repeatOnLifecycle(Lifecycle.State.RESUMED)"))
        assertTrue(activity.contains("withContext(Dispatchers.IO)"))
        assertTrue(activity.contains("BatterySampler.remember"))
        assertTrue(activity.contains("BatterySampler.clear()"))
        assertTrue(activity.contains("WidgetRunState.setPaused(this, true)"))
    }
    @Test fun startupIsIndependentOfWidgetScheduler() {
        val activity = read("java/dev/n2k02/chargewatt/MainActivity.kt")
        val startup = activity.substringAfter("override fun onCreate").substringBefore("lifecycleScope.launch")
        assertFalse(startup.contains("WidgetUpdater.scheduleFallback(this)"))
        assertTrue(startup.contains("Log.e(TAG, \"Dashboard composition startup failed\""))
        assertTrue(activity.contains("catch (cancelled: CancellationException)"))
        assertTrue(activity.contains("Battery polling or widget update failed"))
        assertTrue(activity.contains("Battery observer registration failed"))
        assertTrue(activity.contains("batteryReceiverRegistered"))
        val provider = read("java/dev/n2k02/chargewatt/WidgetReceiver.kt")
        assertTrue(provider.contains("Widget enable failed"))
        assertTrue(provider.contains("Widget update failed"))
    }
    @Test fun releaseWorkManagerConstructorIsKept() {
        val rules = File("proguard-rules.pro").readText()
        assertTrue(rules.contains("-keep class androidx.work.impl.WorkDatabase_Impl"))
        assertTrue(rules.contains("public <init>();"))
        assertTrue(rules.contains("-keep class * extends androidx.room.RoomDatabase"))
        val workflow = File("../.github/workflows/build.yml").readText()
        assertTrue(workflow.contains("verify_release_workmanager.py"))
        assertTrue(workflow.contains("app-release-unsigned.apk"))
        assertTrue(workflow.contains("script: bash scripts/verify_startup_emulator.sh"))
    }
    @Test fun mRadioHeaderAndNavigationRespectSystemBars() {
        val ui = read("java/dev/n2k02/chargewatt/MainActivity.kt")
        assertTrue(ui.contains("enableEdgeToEdge()"))
        assertTrue(ui.contains(".statusBarsPadding()"))
        assertTrue(ui.contains("navigationBarsPadding()"))
        assertTrue(ui.contains("onClick = { page = \"Settings\" }"))
        assertTrue(ui.contains("BackHandler(enabled = page != \"Home\")"))
        assertTrue(ui.contains("SettingsMenuRow("))
        assertTrue(ui.contains("ListItem("))
        assertFalse(ui.contains("page = \"Logs\""))
        assertFalse(ui.contains("\"Logs\" ->"))
        assertFalse(ui.contains("ChargingEventLog"))
        assertFalse(ui.contains("SettingsItem("))
    }
    @Test fun sharedExpressiveThemeAndRealUpdaterAreRequired() {
        val activity = read("java/dev/n2k02/chargewatt/MainActivity.kt")
        assertTrue(activity.contains("N2K02Theme {"))
        assertTrue(activity.contains("N2K02UpdateScreen("))
        assertTrue(activity.contains("N2K02UpdateBadge("))
        assertTrue(activity.contains("N2K02UpdateBadgedIcon("))
        assertFalse(activity.contains("darkColorScheme("))
        assertFalse(activity.contains("Open GitHub releases"))
        val updater = read("java/dev/n2k02/chargewatt/ChargeWattUpdater.kt")
        assertTrue(updater.contains("UpdaterController("))
        assertTrue(updater.contains("PublishedUpdateSource("))
        assertTrue(updater.contains("N2K02/ChargeWatt"))
        assertTrue(updater.contains("onHostResume(activity, installGuard)"))
    }
    @Test fun bothDashboardsSharePaletteWithoutDuplicateHeaders() {
        val activity = read("java/dev/n2k02/chargewatt/MainActivity.kt")
        val ui = read("java/dev/n2k02/chargewatt/BatteryDashboard.kt")
        assertTrue(activity.contains("HomeDashboard("))
        assertTrue(activity.contains("BatteryDetailsDashboard("))
        assertTrue(ui.contains("ChargeWattHero("))
        assertTrue(ui.contains("SectionTitle(\"Electrical\""))
        assertTrue(ui.contains("SectionTitle(\"Battery information\""))
        assertTrue(ui.contains("ChargeWattPalette"))
        assertFalse(ui.contains("NavigationBar("))
        assertFalse(ui.contains("ChargingEventLog"))
        val widget = read("java/dev/n2k02/chargewatt/WidgetReceiver.kt")
        assertTrue(widget.contains("WidgetTextPolicy.secondary(value, unit)"))
        assertTrue(widget.contains("R.color.chargewatt_charging"))
        assertTrue(widget.contains("R.color.chargewatt_discharging"))
    }
    @Test fun widgetIsFixedOneByOne() {
        val provider = read("res/xml/chargewatt_widget_info.xml")
        assertTrue(provider.contains("android:targetCellWidth=\"1\""))
        assertTrue(provider.contains("android:targetCellHeight=\"1\""))
        assertTrue(provider.contains("android:resizeMode=\"none\""))
    }
}
