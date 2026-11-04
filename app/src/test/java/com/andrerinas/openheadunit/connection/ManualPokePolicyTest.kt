package com.andrerinas.openheadunit.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ManualPokePolicyTest {

    @Test
    fun `same active device poke is not restarted`() {
        assertTrue(ManualPokePolicy.isDuplicate("04:C8:B0:C2:9B:C2", "04:C8:B0:C2:9B:C2", active = true))
    }

    @Test
    fun `address comparison is case insensitive`() {
        assertTrue(ManualPokePolicy.isDuplicate("04:c8:b0:c2:9b:c2", "04:C8:B0:C2:9B:C2", active = true))
    }

    @Test
    fun `completed poke can be retried`() {
        assertFalse(ManualPokePolicy.isDuplicate("phone", "phone", active = false))
    }

    @Test
    fun `different address is not classified as the same active target`() {
        assertFalse(ManualPokePolicy.isDuplicate("phone-b", "phone-a", active = true))
    }

    @Test
    fun `no active address allows a poke`() {
        assertFalse(ManualPokePolicy.isDuplicate("phone", null, active = true))
    }

    @Test
    fun `different raw address waits for active poke to release RFCOMM slot`() = runBlocking {
        val coordinator = ManualPokeCoordinator()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = launch {
            coordinator.runSerialized {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()

        val second = async {
            coordinator.runSerialized { secondStarted.complete(Unit) }
        }
        assertFalse(secondStarted.isCompleted)
        releaseFirst.complete(Unit)
        second.await()
        assertTrue(secondStarted.isCompleted)
        first.join()
    }

    @Test
    fun `cancelled active poke releases slot for a deliberate target change`() = runBlocking {
        val coordinator = ManualPokeCoordinator()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = launch {
            coordinator.runSerialized {
                firstStarted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        firstStarted.await()

        first.cancelAndJoin()
        coordinator.runSerialized { secondStarted.complete(Unit) }
        assertTrue(secondStarted.isCompleted)
    }
}
