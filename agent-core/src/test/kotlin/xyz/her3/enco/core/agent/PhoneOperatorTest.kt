package xyz.her3.enco.core.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.her3.enco.core.api.Action
import xyz.her3.enco.core.api.DeviceController
import xyz.her3.enco.core.api.Observation
import xyz.her3.enco.core.api.ScreenElement

class PhoneOperatorTest {
    private val settings = Observation(1, listOf(ScreenElement(1, "藍牙", true, false, false)))
    private val bluetooth = Observation(2, listOf(ScreenElement(1, "可用裝置", false, false, false)))

    @Test
    fun observesAgainAfterTappingBeforeFinishing() =
        runBlocking {
            val device = FakeDevice(listOf(settings, bluetooth))
            val replies =
                ArrayDeque(
                    listOf(
                        """{"action":"tap","target":1}""",
                        """{"action":"finish","success":true,"summary":"已进入蓝牙"}""",
                    ),
                )
            val targets = mutableListOf<String?>()
            val result =
                PhoneOperator(device).run(
                    "进入蓝牙设置",
                    decide = { _, input ->
                        if (replies.size == 1) {
                            assertTrue(input.contains("可用裝置"))
                            assertTrue(input.contains("已点击「藍牙」"))
                        }
                        replies.removeFirst()
                    },
                    onAction = { label ->
                        assertTrue(device.performed.isEmpty())
                        targets.add(label)
                    },
                )
            assertEquals(TaskResult.Finished(true, "已进入蓝牙"), result)
            assertEquals(listOf(Action.Tap(1)), device.performed)
            assertEquals(listOf("藍牙"), targets)
        }

    @Test
    fun blocksSensitiveOrUnknownTargets() =
        runBlocking {
            val unsafe = Observation(1, listOf(ScreenElement(1, "删除", true, false, false)))
            for (target in listOf(1, 2)) {
                val device = FakeDevice(listOf(unsafe))
                val result =
                    PhoneOperator(device).run(
                        "浏览",
                        decide = { _, _ -> """{"action":"tap","target":$target}""" },
                        onAction = { error("被拦截的动作不应显示为正在执行") },
                    )
                assertEquals(TaskResult.TakeOver, result)
                assertTrue(device.performed.isEmpty())
            }
        }

    @Test
    fun retriesInvalidJsonAndNeverRunsUnsupportedCommands() =
        runBlocking {
            val device = FakeDevice(listOf(settings))
            var attempts = 0
            val result =
                PhoneOperator(device).run("浏览", decide = { _, _ ->
                    attempts++
                    """{"action":"shell","command":"anything"}"""
                })
            assertEquals(TaskResult.InvalidReply, result)
            assertEquals(3, attempts)
            assertTrue(device.performed.isEmpty())
        }

    @Test
    fun stopsAfterRepeatedUnchangedScreens() =
        runBlocking {
            val device = FakeDevice(List(4) { settings })
            val result = PhoneOperator(device).run("浏览", decide = { _, _ -> """{"action":"tap","target":1}""" })
            assertEquals(TaskResult.Stalled, result)
            assertEquals(3, device.performed.size)
        }

    @Test
    fun protectedScreenNeverReachesModel() =
        runBlocking {
            val device = FakeDevice(listOf(settings.copy(requiresTakeover = true)))
            val result = PhoneOperator(device).run("浏览", decide = { _, _ -> error("受保护界面不可发给模型") })
            assertEquals(TaskResult.TakeOver, result)
            assertTrue(device.performed.isEmpty())
        }

    private class FakeDevice(
        screens: List<Observation>,
    ) : DeviceController {
        private val frames = ArrayDeque(screens)
        val performed = mutableListOf<Action>()

        override suspend fun observe(): Observation? = frames.removeFirstOrNull()

        override suspend fun perform(
            observation: Observation,
            action: Action,
        ): Boolean = performed.add(action)
    }
}
