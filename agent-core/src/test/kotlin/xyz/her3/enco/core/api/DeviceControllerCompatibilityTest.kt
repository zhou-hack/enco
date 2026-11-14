package xyz.her3.enco.core.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControllerCompatibilityTest {
    private val observation = Observation(1, emptyList())

    @Test
    fun legacyImplementationUsesSafeDefaults() =
        runBlocking {
            val device = LegacyDevice(observation)
            assertEquals(Stage(StageKind.MAIN), device.open(StageRequest(StageKind.MAIN)))
            val unsupported = runCatching { device.open(StageRequest(StageKind.VIRTUAL_DISPLAY)) }.exceptionOrNull()
            assertTrue(unsupported is IllegalArgumentException)
            assertTrue(device.capabilities.isEmpty())
            device.close(keepApp = true)
        }

    @Test
    fun defaultPerformDelegatesToLegacyObservationAndAction() =
        runBlocking {
            val device = LegacyDevice(observation)
            val result = device.perform(Action.Tap(1))
            assertTrue(result.success)
            assertEquals(listOf(Action.Tap(1)), device.performed)

            val unavailable = LegacyDevice(null)
            assertFalse(unavailable.perform(Action.Home).success)
            assertTrue(unavailable.performed.isEmpty())
        }

    private class LegacyDevice(
        private val screen: Observation?,
    ) : DeviceController {
        val performed = mutableListOf<Action>()

        override suspend fun observe(): Observation? = screen

        override suspend fun perform(
            observation: Observation,
            action: Action,
        ): Boolean = performed.add(action)
    }
}
