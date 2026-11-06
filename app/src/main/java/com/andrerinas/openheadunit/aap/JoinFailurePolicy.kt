package com.andrerinas.openheadunit.aap

/**
 * The phone reports it cannot join the car's hotspot, typically because its own Wi-Fi hotspot is on:
 * it can't be a client at the same time. Asking again every 15 s only drains it, so the tries space
 * out and the driver is told.
 */
object JoinFailurePolicy {
    private const val BASE_RETRY_MS = 15_000L
    private const val MAX_RETRY_MS = 60_000L
    private const val WARN_FROM = 2
    private const val WARN_EVERY = 5

    fun retryDelayMs(failures: Int): Long = when {
        failures < WARN_FROM -> BASE_RETRY_MS
        failures == WARN_FROM -> BASE_RETRY_MS * 2
        else -> MAX_RETRY_MS
    }

    fun shouldWarn(failures: Int): Boolean =
        failures >= WARN_FROM && (failures - WARN_FROM) % WARN_EVERY == 0
}
