package xyz.her3.enco.device.shell

import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.system.exitProcess

class EncoShellService : IEncoShellService.Stub() {
    init {
        check(HiddenApiBypass.addHiddenApiExemptions("")) { "无法初始化助手进程的隐藏 API 访问" }
    }

    override fun getProcessUid(): Int = Process.myUid()

    override fun destroy() = exitProcess(0)
}
