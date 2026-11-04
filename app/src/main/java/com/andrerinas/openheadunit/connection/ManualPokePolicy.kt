package com.andrerinas.openheadunit.connection

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ManualPokePolicy {
    fun isDuplicate(requestedAddress: String, activeAddress: String?, active: Boolean): Boolean =
        active && activeAddress?.equals(requestedAddress, ignoreCase = true) == true
}

internal class ManualPokeCoordinator {
    private val mutex = Mutex()

    suspend fun <T> runSerialized(block: suspend () -> T): T = mutex.withLock { block() }
}
