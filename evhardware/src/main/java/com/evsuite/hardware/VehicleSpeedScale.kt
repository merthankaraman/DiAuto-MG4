package com.evsuite.hardware

import kotlin.math.abs

/**
 * What `PERF_VEHICLE_SPEED` is actually in.
 *
 * AAOS defines the property as metres per second, but SAIC's firmwares report km/h, as MG4 Dash and
 * MG4Control read it. Multiplying by 3.6 scaled every derived distance by the same factor. Measured:
 * on SWI68 R67, a 2.4 km drive recorded as 8.46 and 7.69 km against the adapter's odometer; on SWI69
 * R45, a recorded trip with 48 s at 183 km/h on a short town drive.
 */
object VehicleSpeedScale {

    /**
     * The raw property value as km/h.
     *
     * The property is signed — negative in reverse — and every consumer here asks whether the
     * car is moving, so the magnitude is what comes back.
     */
    fun toKmh(raw: Float): Float = abs(raw)
}
