package xyz.her3.enco.device.stage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.her3.enco.core.api.Action
import xyz.her3.enco.core.api.StageKind
import xyz.her3.enco.core.api.StageRequest
import xyz.her3.enco.device.shell.IEncoShellService
import xyz.her3.enco.device.shell.ScreenFrame
import xyz.her3.enco.device.shell.SettingsFrame
import xyz.her3.enco.device.shell.ShellFrame
import xyz.her3.enco.device.shell.ShellNode

class MainScreenDeviceTest {
    @Test
    fun observeMapsNodesAndCompressesElements() =
        runBlocking {
            val service = FakeShell()
            val device = MainScreenDevice(service)

            val observation = device.observe()

            assertNotNull(observation)
            assertEquals("com.android.settings", observation?.packageName)
            assertEquals(2, observation?.rawNodes?.size)
            assertEquals(1, observation?.elements?.size)
            assertEquals("搜索", observation?.elements?.single()?.label)
            assertTrue(observation?.elements?.single()?.canEdit == true)
        }

    @Test
    fun tapUsesElementCenterAndTypeUsesEditPath() =
        runBlocking {
            val service = FakeShell()
            val device = MainScreenDevice(service)
            val observation = requireNotNull(device.observe())
            val element = observation.elements.single()

            assertTrue(device.perform(observation, Action.Tap(element.id)))
            assertEquals(listOf(500, 50), service.tapArgs)

            assertTrue(device.perform(observation, Action.Type(element.id, "亮度", submit = true)))
            assertEquals(listOf(0, 0), service.setTextPath?.toList())
            assertEquals("亮度", service.setTextValue)
            assertTrue(service.enterPressed)
        }

    @Test
    fun launchRoutesPackageAndKnownNamesAndRejectsUnknown() =
        runBlocking {
            val service = FakeShell()
            val device = MainScreenDevice(service)

            assertTrue(device.launchApp("com.android.settings").success)
            assertEquals("com.android.settings", service.launchedPackage)
            assertTrue(device.launchApp("设置").success)
            assertEquals(IEncoShellService.APP_SETTINGS, service.launchedApp)
            assertFalse(device.launchApp("bad package;rm").success)
            assertFalse(device.perform(Action.Finish(true, "done")).success)
        }

    @Test
    fun openRejectsNonMainStage() =
        runBlocking {
            val device = MainScreenDevice(FakeShell())
            val error = runCatching { device.open(StageRequest(StageKind.VIRTUAL_DISPLAY)) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }

    private class FakeShell : IEncoShellService {
        var tapArgs: List<Int>? = null
        var setTextPath: IntArray? = null
        var setTextValue: String? = null
        var enterPressed = false
        var launchedPackage: String? = null
        var launchedApp: Int? = null

        override fun observeMainScreen(): ShellFrame =
            ShellFrame().apply {
                id = 7
                image = null
                packageName = "com.android.settings"
                width = 1000
                height = 2000
                nodes =
                    arrayOf(
                        ShellNode().apply {
                            nodePath = intArrayOf(0)
                            left = 0
                            top = 0
                            right = 1000
                            bottom = 200
                            packageName = "com.android.settings"
                            visible = true
                            enabled = true
                        },
                        ShellNode().apply {
                            nodePath = intArrayOf(0, 0)
                            left = 0
                            top = 0
                            right = 1000
                            bottom = 200
                            packageName = "com.android.settings"
                            text = "搜索"
                            visible = true
                            enabled = true
                            canEdit = true
                        },
                    )
            }

        override fun tap(
            frameId: Long,
            x: Int,
            y: Int,
        ): Boolean {
            tapArgs = listOf(x, y)
            return true
        }

        override fun setText(
            frameId: Long,
            path: IntArray,
            text: String,
        ): Boolean {
            setTextPath = path
            setTextValue = text
            return true
        }

        override fun pressKey(keyCode: Int): Boolean {
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER) enterPressed = true
            return true
        }

        override fun launchPackage(packageName: String): Boolean {
            launchedPackage = packageName
            return true
        }

        override fun launchApp(app: Int): Boolean {
            launchedApp = app
            return true
        }

        override fun asBinder(): android.os.IBinder? = null

        override fun getProcessUid(): Int = 2000

        override fun captureScreen(): ScreenFrame = ScreenFrame()

        override fun swipe(
            frameId: Long,
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
        ): Boolean = true

        override fun pressBack(): Boolean = true

        override fun pressHome(): Boolean = true

        override fun observeSettings(): SettingsFrame? = null

        override fun performSettingsAction(
            frameId: Long,
            target: Int,
            command: Int,
        ): Boolean = false

        override fun pasteWeChatText(
            frameId: Long,
            text: String,
        ): Boolean = false

        override fun observeGmail(): String? = null

        override fun actInGmail(
            frameId: Long,
            target: Int,
            query: String?,
        ): Boolean = false

        override fun grantSelfPermissions(): Int = 0

        override fun longPress(
            frameId: Long,
            x: Int,
            y: Int,
        ): Boolean = true

        override fun destroy() = Unit
    }
}
