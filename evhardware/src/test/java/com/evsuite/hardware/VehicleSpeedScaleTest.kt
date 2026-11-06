package com.evsuite.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleSpeedScaleTest {

    @Test fun `the property is already in km per hour`() {
        assertEquals(34.4f, VehicleSpeedScale.toKmh(34.4f), 0.001f)
    }

    @Test fun `reverse is a speed, not a negative one`() {
        assertEquals(12.0f, VehicleSpeedScale.toKmh(-12.0f), 0.001f)
    }

    @Test fun `the drive that proved it now integrates to the route it followed`() {
        // The recorded trip: 245836 ms at a steady 34.4 km/h reads as 2.35 km, not 8.46 km.
        val hours = 245_836L / 3_600_000.0
        assertEquals(2.35, VehicleSpeedScale.toKmh(34.4f) * hours, 0.02)
    }

    @Test fun `standstill stays standstill`() {
        assertEquals(0.0f, VehicleSpeedScale.toKmh(0.0f), 0.0f)
    }
}
