package dev.n2k02.chargewatt

import android.os.BatteryManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPowerStateTrackerTest {
    @Test fun detectsTransitionsAcrossUsbQiAndUnplugged() {
        val tracker = WidgetPowerStateTracker()
        assertTrue(tracker.changed(0))
        assertFalse(tracker.changed(0))
        assertTrue(tracker.changed(BatteryManager.BATTERY_PLUGGED_USB))
        assertFalse(tracker.changed(BatteryManager.BATTERY_PLUGGED_USB))
        assertTrue(tracker.changed(BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertFalse(tracker.changed(BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertTrue(tracker.changed(0))
        assertFalse(tracker.changed(0))
    }

    @Test fun combinedPlugFlagsCountAsTransitions() {
        val tracker = WidgetPowerStateTracker()
        val wireless = BatteryManager.BATTERY_PLUGGED_WIRELESS
        val usb = BatteryManager.BATTERY_PLUGGED_USB
        assertTrue(tracker.changed(usb))
        assertTrue(tracker.changed(usb or wireless))
        assertTrue(tracker.changed(wireless))
        assertFalse(tracker.changed(wireless))
    }
}
