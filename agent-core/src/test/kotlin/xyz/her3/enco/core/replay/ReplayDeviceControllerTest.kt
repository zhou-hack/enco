package xyz.her3.enco.core.replay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.her3.enco.core.api.Action
import xyz.her3.enco.core.api.IntRect

class ReplayDeviceControllerTest {
    @Test
    fun replaysBluetoothSettingsInOrder() =
        runBlocking {
            val device = FakeDeviceController(ReplayLoader().load("settings_bluetooth"))
            val settings = requireNotNull(device.observe())
            val bluetooth = settings.elements.single { it.label == "蓝牙" }
            assertEquals(listOf(0, 1), bluetooth.nodePath)
            assertEquals(IntRect(0, 100, 1000, 150), bluetooth.bounds)
            assertTrue(device.perform(settings, Action.Tap(bluetooth.id)))

            val bluetoothSettings = requireNotNull(device.observe())
            assertEquals(2L, bluetoothSettings.id)
            val deviceList = bluetoothSettings.elements.single { it.label == "蓝牙设备列表" }
            assertTrue(deviceList.canScrollForward)
            assertTrue(device.perform(bluetoothSettings, Action.Scroll(deviceList.id, forward = true)))

            val devices = requireNotNull(device.observe())
            assertEquals(3L, devices.id)
            assertTrue(devices.elements.any { it.label == "我的耳机" })
            assertEquals("com.android.settings", devices.packageName)
            assertEquals(
                listOf(Action.Tap(bluetooth.id), Action.Scroll(deviceList.id, forward = true)),
                device.actions,
            )
        }

    @Test
    fun supportsNoScreen() =
        runBlocking {
            val device = FakeDeviceController(emptyList())

            assertNull(device.observe())
            assertFalse(device.perform(Action.Home).success)
            assertTrue(device.actions.isEmpty())
        }

    @Test
    fun failedActionIsRecordedButDoesNotAdvanceFrame() =
        runBlocking {
            val device = FakeDeviceController(ReplayLoader().load("settings_bluetooth"))
            val settings = requireNotNull(device.observe())
            val bluetooth = settings.elements.single { it.label == "蓝牙" }
            val action = Action.Tap(bluetooth.id)
            device.failNextAction = true

            assertFalse(device.perform(settings, action))
            assertEquals(settings, device.observe())
            assertEquals(listOf(action), device.actions)

            assertTrue(device.perform(settings, action))
            assertEquals(2L, device.observe()?.id)
            assertEquals(listOf(action, action), device.actions)
        }

    @Test
    fun staleFrameCannotPerformOrAdvance() =
        runBlocking {
            val device = FakeDeviceController(ReplayLoader().load("settings_bluetooth"))
            val stale = requireNotNull(device.observe())
            val bluetooth = stale.elements.single { it.label == "蓝牙" }
            assertTrue(device.perform(stale, Action.Tap(bluetooth.id)))
            val current = requireNotNull(device.observe())

            assertFalse(device.perform(stale, Action.Back))

            assertEquals(current, device.observe())
            assertEquals(listOf(Action.Tap(bluetooth.id)), device.actions)
        }
}
