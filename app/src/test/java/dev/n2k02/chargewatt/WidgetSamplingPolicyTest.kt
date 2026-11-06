package dev.n2k02.chargewatt

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WidgetSamplingPolicyTest {
    @Test fun fallbackOnlyRunsWhenNoLiveSampler() {
        assertTrue(WidgetSamplingPolicy.shouldSample(
            paused = false, widgetsInstalled = true,
            activityResumed = false, serviceRunning = false))
        assertFalse(WidgetSamplingPolicy.shouldSample(
            paused = false, widgetsInstalled = true,
            activityResumed = true, serviceRunning = false))
        assertFalse(WidgetSamplingPolicy.shouldSample(
            paused = false, widgetsInstalled = true,
            activityResumed = false, serviceRunning = true))
        assertFalse(WidgetSamplingPolicy.shouldSample(
            paused = true, widgetsInstalled = true,
            activityResumed = false, serviceRunning = false))
        assertFalse(WidgetSamplingPolicy.shouldSample(
            paused = false, widgetsInstalled = false,
            activityResumed = false, serviceRunning = false))
    }

    @Test fun serviceShutdownAlwaysClearsSamplingLease() {
        val src = listOf(
            File("src/main/java/dev/n2k02/chargewatt/WidgetPollingService.kt"),
            File("app/src/main/java/dev/n2k02/chargewatt/WidgetPollingService.kt")
        ).first { it.exists() }.readText()
        assertTrue(src.contains("WidgetPollingActivityState.serviceRunning = true"))
        val onDestroy = src.substringAfter("override fun onDestroy()")
            .substringBefore("companion object")
        assertTrue(onDestroy.contains("WidgetPollingActivityState.serviceRunning = false"))
        assertTrue(onDestroy.contains("scope.cancel()"))
        assertTrue(onDestroy.contains("unregisterReceiver(batteryReceiver)"))
        val widget = listOf(
            File("src/main/java/dev/n2k02/chargewatt/WidgetReceiver.kt"),
            File("app/src/main/java/dev/n2k02/chargewatt/WidgetReceiver.kt")
        ).first { it.exists() }.readText()
        assertTrue(widget.contains("WidgetSamplingPolicy.shouldSample("))
        assertTrue(widget.contains("WidgetUpdater.refresh(app)"))
    }
}
