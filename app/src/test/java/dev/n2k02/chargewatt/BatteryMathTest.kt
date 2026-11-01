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
    @Test fun powerUsesMicroampsAndMillivolts() {
        assertEquals(1.7505, BatteryMath.watts(450000, 3890)!!, 0.0001)
        assertEquals(1.7505, BatteryMath.watts(-450000, 3890)!!, 0.0001)
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
        assertEquals("−1.75 W", example(ChargeMode.DISCHARGING).signedWatts)
        assertEquals("+450 mA", example(ChargeMode.CHARGING).signedCurrent)
        assertEquals("−450 mA", example(ChargeMode.DISCHARGING).signedCurrent)
        assertEquals("3.89 V", example(ChargeMode.CHARGING).voltageText)
    }
    @Test fun fingerprintIgnoresTimestampsButTracksUnitAndTheme() {
        val a = example(ChargeMode.CHARGING)
        val b = a.copy(sampledAtMs = 9999)
        assertEquals(a.fingerprint("W", "dark"), b.fingerprint("W", "dark"))
        assertNotEquals(a.fingerprint("W", "dark"), b.fingerprint("mA", "dark"))
        assertNotEquals(a.fingerprint("W", "dark"), b.fingerprint("W", "transparent"))
    }
    @Test fun missingMeasurementsHaveExplicitPlaceholder() {
        val empty = example(ChargeMode.DISCHARGING).copy(powerW = null, currentMa = null, voltageV = null)
        assertEquals("— W", empty.signedWatts)
        assertEquals("— mA", empty.signedCurrent)
        assertEquals("— V", empty.voltageText)
    }
}
