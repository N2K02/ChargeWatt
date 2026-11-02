package dev.n2k02.chargewatt

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

class PowerConnectionPolicyTest {
    @Test fun refreshOnPowerConnected() {
        assertTrue(PowerConnectionPolicy.shouldRefresh(Intent.ACTION_POWER_CONNECTED))
    }

    @Test fun refreshOnPowerDisconnected() {
        assertTrue(PowerConnectionPolicy.shouldRefresh(Intent.ACTION_POWER_DISCONNECTED))
    }

    @Test fun ignoreUnrelatedEvents() {
        assertFalse(PowerConnectionPolicy.shouldRefresh(Intent.ACTION_BATTERY_CHANGED))
        assertFalse(PowerConnectionPolicy.shouldRefresh(null))
        assertFalse(PowerConnectionPolicy.shouldRefresh(Intent.ACTION_SCREEN_ON))
    }
}
