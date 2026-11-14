package xyz.her3.enco.device.shell

import android.Manifest
import android.os.Build
import android.util.Log
import xyz.her3.enco.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object ShellPermissionGranter {
    const val OVERLAY_COMMAND_SUCCEEDED = 1
    const val NOTIFICATION_COMMAND_SUCCEEDED = 1 shl 1
    private const val LOG_TAG = "enco.device"
    private const val COMMAND_TIMEOUT_SECONDS = 10L

    fun grant(): Int {
        var result = 0
        val overlayArgs =
            listOf(
                "/system/bin/appops",
                "set",
                BuildConfig.APPLICATION_ID,
                "SYSTEM_ALERT_WINDOW",
                "allow",
            )
        if (runCommand(overlayArgs)) result = result or OVERLAY_COMMAND_SUCCEEDED

        val notificationGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                runCommand(
                    listOf(
                        "/system/bin/pm",
                        "grant",
                        BuildConfig.APPLICATION_ID,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ),
                )
        if (notificationGranted) result = result or NOTIFICATION_COMMAND_SUCCEEDED
        return result
    }

    private fun runCommand(arguments: List<String>): Boolean {
        val process =
            try {
                ProcessBuilder(arguments).redirectErrorStream(true).start()
            } catch (error: IOException) {
                Log.w(LOG_TAG, "无法启动固定授权命令", error)
                return false
            }
        return try {
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(LOG_TAG, "固定授权命令执行超时")
                return false
            }
            process.exitValue() == 0
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(LOG_TAG, "固定授权命令被中断", error)
            false
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
