package xyz.her3.enco.device.shell

import android.os.Binder
import android.os.Process
import android.view.KeyEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.system.exitProcess

class EncoShellService : IEncoShellService.Stub() {
    init {
        check(HiddenApiBypass.addHiddenApiExemptions("")) { "无法初始化助手进程的隐藏 API 访问" }
    }

    private val screen = MainScreenAutomation()

    override fun getProcessUid(): Int = Process.myUid()

    override fun captureScreen(): ScreenFrame = runAsService { screen.capture() }

    override fun tap(
        frameId: Long,
        x: Int,
        y: Int,
    ): Boolean = runAsService { screen.touch(frameId, x, y, x, y, swipe = false) }

    override fun swipe(
        frameId: Long,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
    ): Boolean = runAsService { screen.touch(frameId, fromX, fromY, toX, toY, swipe = true) }

    override fun pressBack(): Boolean = runAsService { screen.pressKey(KeyEvent.KEYCODE_BACK) }

    override fun pressHome(): Boolean = runAsService { screen.pressKey(KeyEvent.KEYCODE_HOME) }

    override fun observeSettings(): SettingsFrame? = runAsService { screen.observeSettings() }

    override fun performSettingsAction(
        frameId: Long,
        target: Int,
        command: Int,
    ): Boolean = runAsService { screen.performSettingsAction(frameId, target, command) }

    override fun destroy() = exitProcess(0)

    override fun pasteWeChatText(
        frameId: Long,
        text: String,
    ): Boolean = runAsService { screen.pasteWeChatText(frameId, text) }

    override fun observeGmail(): String? = runAsService { screen.observeGmail() }

    override fun actInGmail(
        frameId: Long,
        target: Int,
        query: String?,
    ): Boolean = runAsService { screen.actInGmail(frameId, target, query) }

    override fun launchApp(app: Int): Boolean = runAsService { ShellAppLauncher.launch(app) }

    override fun grantSelfPermissions(): Int = runAsService { ShellPermissionGranter.grant() }

    override fun observeMainScreen(): ShellFrame = runAsService { screen.observe() }

    override fun longPress(
        frameId: Long,
        x: Int,
        y: Int,
    ): Boolean = runAsService { screen.longPress(frameId, x, y) }

    override fun pressKey(keyCode: Int): Boolean = runAsService { screen.pressKey(keyCode) }

    override fun launchPackage(packageName: String): Boolean = runAsService { screen.launchPackage(packageName) }

    override fun setText(
        frameId: Long,
        path: IntArray,
        text: String,
    ): Boolean = runAsService { screen.setText(frameId, path, text) }

    @Synchronized
    private fun <T> runAsService(action: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        try {
            return action()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
}
