package dev.n2k02.chargewatt

import org.junit.Assert.*
import org.junit.Test

class BatteryMathTest {
    @Test fun defaultAndBounds() {
        assertEquals(1, BatteryMath.DEFAULT_REFRESH_SECONDS)
        assertEquals(1, BatteryMath.refreshSeconds(-5))
        assertEquals(1, BatteryMath.refreshSeconds(1))
        assertEquals(5, BatteryMath.refreshSeconds(5))
        assertEquals(10, BatteryMath.refreshSeconds(10))
        assertEquals(10, BatteryMath.refreshSeconds(999))
    }
    @Test fun powerSourceIsBatteryWhenUnplugged() {
        assertEquals(PowerSource.BATTERY, BatteryMath.source(0))
        assertEquals(PowerSource.AC, BatteryMath.source(android.os.BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals(PowerSource.USB, BatteryMath.source(android.os.BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals(PowerSource.WIRELESS, BatteryMath.source(android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertEquals("Battery", example(ChargeMode.DISCHARGING)
            .copy(source = PowerSource.BATTERY).powerSourceText)
        assertEquals("Wireless Qi", example(ChargeMode.CHARGING)
            .copy(source = PowerSource.WIRELESS).powerSourceText)
    }
    @Test fun wirelessQiDiagnosticsExposeTheRawPluggedExtraAndStatus() {
        val details = BatteryEventDiagnostics.describe(
            android.os.BatteryManager.BATTERY_STATUS_CHARGING,
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS
        )
        assertTrue(details.contains("EXTRA_STATUS=2(CHARGING)"))
        assertTrue(details.contains("EXTRA_PLUGGED=4(source=WIRELESS, wirelessQi=true)"))
        val unplugged = BatteryEventDiagnostics.describe(
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING, 0
        )
        assertTrue(unplugged.contains("wirelessQi=false"))
        val missingPluggedExtra = BatteryEventDiagnostics.describe(
            android.os.BatteryManager.BATTERY_STATUS_UNKNOWN, -1
        )
        assertTrue(missingPluggedExtra.contains("source=UNKNOWN, wirelessQi=false"))
    }

    @Test fun powerUsesMicroampsAndMillivolts() {
        assertEquals(1.7505, BatteryMath.watts(450000, 3890)!!, 0.0001)
        assertEquals(-1.7505, BatteryMath.watts(-450000, 3890)!!, 0.0001)
        assertEquals(0.0, BatteryMath.watts(0, 3890)!!, 0.0)
    }
    @Test fun invalidDataIsNeverInvented() {
        assertNull(BatteryMath.watts(null, 3890))
        assertNull(BatteryMath.watts(450000, null))
        assertNull(BatteryMath.watts(Int.MIN_VALUE, 3890))
        assertNull(BatteryMath.watts(20000001, 3890))
        assertNull(BatteryMath.watts(450000, 0))
    }
    private fun example(mode: ChargeMode) = BatteryReading(
        36, 450.0, 3.89, 1.7505, 38.7, mode, PowerSource.USB, "Good", null, 1L
    )
    @Test fun chargingAndDischargingSigns() {
        assertEquals("+1.75 W", example(ChargeMode.CHARGING).signedWatts)
        assertEquals("−1.75 W", example(ChargeMode.DISCHARGING).copy(powerW = -1.7505).signedWatts)
        assertEquals("+450 mA", example(ChargeMode.CHARGING).signedCurrent)
        assertEquals("−450 mA", example(ChargeMode.DISCHARGING).copy(currentMa = -450.0).signedCurrent)
        assertEquals("3.89 V", example(ChargeMode.CHARGING).voltageText)
    }
    @Test fun fingerprintIgnoresTimestampsButTracksUnitAndTheme() {
        val a = example(ChargeMode.CHARGING)
        val b = a.copy(sampledAtMs = 9999)
        assertEquals(a.fingerprint("W", "dark"), b.fingerprint("W", "dark"))
        assertNotEquals(a.fingerprint("W", "dark"), b.fingerprint("mA", "dark"))
        assertNotEquals(a.fingerprint("W", "dark"), b.fingerprint("W", "transparent"))
    }
    @Test fun netDrainOnChargerIsShownAsNegative() {
        val pluggedAndDraining = example(ChargeMode.CHARGING).copy(currentMa = -500.0, powerW = -1.945)
        assertEquals("−1.95 W", pluggedAndDraining.signedWatts)
        assertEquals("−500 mA", pluggedAndDraining.signedCurrent)
        assertEquals("Plugged in · net drain", pluggedAndDraining.statusText)
        assertEquals("0.00 W", BatteryMath.formatSigned(0.0, "W", 2))
        assertEquals("— W", BatteryMath.formatSigned(Double.NaN, "W", 2))
    }
    @Test fun unknownStatusMustNeverBeMisclassifiedAsDischarging() {
        assertEquals(ChargeMode.IDLE,
            BatteryMath.mode(android.os.BatteryManager.BATTERY_STATUS_UNKNOWN, 0))
        assertEquals(ChargeMode.DISCHARGING,
            BatteryMath.mode(android.os.BatteryManager.BATTERY_STATUS_DISCHARGING, 0))
        assertEquals(ChargeMode.CHARGING,
            BatteryMath.mode(android.os.BatteryManager.BATTERY_STATUS_CHARGING, 1))
    }
    @Test fun batteryStatusChangesTriggerForcedRefreshEvenWhenPluggedFlagIsUnchanged() {
        val tracker = WidgetPowerStateTracker()
        val wireless = android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS
        assertTrue(tracker.changed(android.os.BatteryManager.BATTERY_STATUS_CHARGING, wireless))
        assertFalse(tracker.changed(android.os.BatteryManager.BATTERY_STATUS_CHARGING, wireless))
        assertTrue(tracker.changed(android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING, wireless))
        assertTrue(tracker.changed(android.os.BatteryManager.BATTERY_STATUS_DISCHARGING, wireless))
    }

    @Test fun policyLimitsPartialUpdateHistory() {
        assertTrue(WidgetUpdatePolicy.needsFull(false, false, null, 1000L))
        assertTrue(WidgetUpdatePolicy.needsFull(true, false, 1000L, 1001L))
        assertTrue(WidgetUpdatePolicy.needsFull(false, true, 1000L, 1001L))
        assertFalse(WidgetUpdatePolicy.needsFull(false, false, 1000L, 59999L))
        assertTrue(WidgetUpdatePolicy.needsFull(false, false, 1000L, 61000L))
        assertTrue(WidgetUpdatePolicy.needsFull(false, false, 1000L, 999L))
    }
    @Test fun unchangedReadingIgnoresTimestampForCompose() {
        val source = example(ChargeMode.CHARGING)
        assertTrue(source.sameContent(source.copy(sampledAtMs = source.sampledAtMs + 1000)))
        assertFalse(source.sameContent(source.copy(currentMa = 451.0)))
    }
    @Test fun widgetDisplaysQiEvenWhenNetCurrentIsNegative() {
        val plugged = example(ChargeMode.CHARGING).copy(
            currentMa = -350.0, powerW = -1.36, source = PowerSource.WIRELESS
        )
        assertEquals("Wireless Qi · net battery drain", plugged.statusText)
        assertEquals("Discharging",
            plugged.copy(mode = ChargeMode.DISCHARGING).statusText)
        assertEquals("Not charging",
            plugged.copy(mode = ChargeMode.IDLE).statusText)
        assertEquals("Wireless Qi", plugged.copy(currentMa = 350.0,
            powerW = 1.36, mode = ChargeMode.CHARGING).statusText)
        assertEquals(PowerSource.WIRELESS,
            BatteryMath.source(android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS or
                android.os.BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals("Wireless Qi", plugged.powerSourceText)
        assertEquals("−1.36W", WidgetTextPolicy.primary(plugged, "W"))
        assertEquals("−350mA", WidgetTextPolicy.primary(plugged, "mA"))
    }
    @Test fun compactVoltageAndWidgetStateIcons() {
        assertEquals("3.94V", WidgetTextPolicy.voltage(3.94))
        assertEquals("—V", WidgetTextPolicy.voltage(null))
        val sample = example(ChargeMode.CHARGING)
        assertEquals(WidgetChargeIcon.AC,
            WidgetTextPolicy.chargeIcon(sample.copy(source = PowerSource.AC)))
        assertEquals(WidgetChargeIcon.WIRELESS,
            WidgetTextPolicy.chargeIcon(sample.copy(source = PowerSource.WIRELESS)))
        assertEquals(WidgetChargeIcon.DISCHARGING,
            WidgetTextPolicy.chargeIcon(sample.copy(mode = ChargeMode.DISCHARGING)))
        assertEquals(WidgetChargeIcon.FULL,
            WidgetTextPolicy.chargeIcon(sample.copy(mode = ChargeMode.FULL)))
        assertEquals(WidgetChargeIcon.IDLE,
            WidgetTextPolicy.chargeIcon(sample.copy(mode = ChargeMode.IDLE)))
    }
    @Test fun batteryLevelAndTemperatureChangesRefreshVisibleWidgetMetadata() {
        val first = example(ChargeMode.CHARGING)
        assertNotEquals(first.fingerprint("W", "dark"),
            first.copy(level = 70).fingerprint("W", "dark"))
        assertNotEquals(first.fingerprint("W", "dark"),
            first.copy(temperatureC = 39.2).fingerprint("W", "dark"))
        assertEquals("39°", WidgetTextPolicy.temperature(first.copy(temperatureC = 38.7)))
        assertEquals("07°", WidgetTextPolicy.temperature(first.copy(temperatureC = 7.2)))
        assertEquals("82%", WidgetTextPolicy.level(first.copy(level = 82)))
        assertEquals("—°", WidgetTextPolicy.temperature(first.copy(temperatureC = null)))
        assertEquals("—%", WidgetTextPolicy.level(first.copy(level = null)))
    }

    @Test fun currentAbbreviationPreventsFourDigitClipping() {
        assertEquals("−1.33A", WidgetTextPolicy.current(-1331.0))
        assertEquals("+2.45A", WidgetTextPolicy.current(2450.0))
        assertEquals("−363mA", WidgetTextPolicy.current(-363.0))
        assertEquals("−10.0A", WidgetTextPolicy.current(-10000.0))
        assertEquals("0mA", WidgetTextPolicy.current(0.0))
        assertEquals("—mA", WidgetTextPolicy.current(null))
        assertEquals("—W", WidgetTextPolicy.power(null))
        assertEquals("−5.28W", WidgetTextPolicy.power(-5.28))
        val reading = example(ChargeMode.DISCHARGING).copy(currentMa = -1331.0, powerW = -5.28)
        assertEquals("−1.33A", WidgetTextPolicy.primary(reading, "mA"))
        assertEquals("−5.28W", WidgetTextPolicy.secondary(reading, "mA"))
    }
    @Test fun missingMeasurementsHaveExplicitPlaceholder() {
        val empty = example(ChargeMode.DISCHARGING).copy(powerW = null, currentMa = null, voltageV = null)
        assertEquals("— W", empty.signedWatts)
        assertEquals("— mA", empty.signedCurrent)
        assertEquals("— V", empty.voltageText)
    }
}
