package xyz.her3.enco.device.shell

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object ShellAppLauncher {
    fun launch(app: Int): Boolean {
        val target =
            when (app) {
                IEncoShellService.APP_SETTINGS -> {
                    listOf("-a", "android.settings.SETTINGS", "-p", SETTINGS_PACKAGE)
                }

                IEncoShellService.APP_WECHAT -> {
                    listOf("-n", "com.tencent.mm/.ui.LauncherUI")
                }

                IEncoShellService.APP_GMAIL -> {
                    listOf("-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", GmailInboxReader.PACKAGE)
                }

                else -> {
                    return false
                }
            }
        val process =
            try {
                ProcessBuilder(listOf("/system/bin/am", "start", "-W", "--user", "current") + target)
                    .redirectErrorStream(true)
                    .start()
            } catch (error: IOException) {
                Log.w("enco.device", "无法启动 Activity Manager", error)
                return false
            }
        return try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                Log.w("enco.device", "启动目标应用超时，目标编号=$app")
                return false
            }
            // 部分系统在 Intent 解析失败时仍返回退出码 0。
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val started = process.exitValue() == 0 && output.lineSequence().any { it.trim() == "Status: ok" }
            if (!started) Log.w("enco.device", "目标应用启动失败，目标编号=$app")
            started
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
