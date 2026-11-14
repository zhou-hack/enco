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

    override fun readGmailInbox(count: Int): Array<String>? = runAsService { screen.readGmailInbox(count) }

    override fun launchApp(app: Int): Boolean = runAsService { ShellAppLauncher.launch(app) }

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
