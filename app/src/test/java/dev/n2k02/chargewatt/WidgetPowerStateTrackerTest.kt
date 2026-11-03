package dev.n2k02.chargewatt

import android.os.BatteryManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPowerStateTrackerTest {
    @Test fun detectsTransitionsAcrossUsbQiAndUnplugged() {
        val tracker = WidgetPowerStateTracker()
        val discharging = BatteryManager.BATTERY_STATUS_DISCHARGING
        val charging = BatteryManager.BATTERY_STATUS_CHARGING
        val usb = BatteryManager.BATTERY_PLUGGED_USB
        val wireless = BatteryManager.BATTERY_PLUGGED_WIRELESS
        val unplugged = 0

        assertTrue(tracker.changed(discharging, unplugged))
        assertFalse(tracker.changed(discharging, unplugged))
        assertTrue(tracker.changed(charging, usb))
        assertFalse(tracker.changed(charging, usb))
        assertTrue(tracker.changed(charging, wireless))
        assertFalse(tracker.changed(charging, wireless))
        assertTrue(tracker.changed(discharging, unplugged))
        assertFalse(tracker.changed(discharging, unplugged))
    }

    @Test fun combinedPlugFlagsCountAsTransitions() {
        val tracker = WidgetPowerStateTracker()
        val charging = BatteryManager.BATTERY_STATUS_CHARGING
        val wireless = BatteryManager.BATTERY_PLUGGED_WIRELESS
        val usb = BatteryManager.BATTERY_PLUGGED_USB
        assertTrue(tracker.changed(charging, usb))
        assertTrue(tracker.changed(charging, usb or wireless))
        assertTrue(tracker.changed(charging, wireless))
        assertFalse(tracker.changed(charging, wireless))
    }

    @Test fun statusChangesAreDetectedEvenWhenPlugSourceDoesNotChange() {
        val tracker = WidgetPowerStateTracker()
        val plugged = BatteryManager.BATTERY_PLUGGED_USB
        assertTrue(tracker.changed(BatteryManager.BATTERY_STATUS_CHARGING, plugged))
        assertTrue(tracker.changed(BatteryManager.BATTERY_STATUS_FULL, plugged))
        assertFalse(tracker.changed(BatteryManager.BATTERY_STATUS_FULL, plugged))
    }
}
