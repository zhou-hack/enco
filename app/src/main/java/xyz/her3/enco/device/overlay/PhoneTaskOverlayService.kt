package xyz.her3.enco.device.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.her3.enco.R
import xyz.her3.enco.device.service.PhoneTaskService
import xyz.her3.enco.ui.MainActivity

class PhoneTaskOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (taskOverlay != null || shortcutOverlay != null) return START_NOT_STICKY
        startForeground(NOTIFICATION, notification())
        val task =
            PhoneTaskOverlay(
                this,
                onOpen = ::openEnco,
                onStop = { stopService(Intent(this, PhoneTaskService::class.java)) },
                onDismiss = { taskOverlay?.dismissResult() },
                onPermissionRevoked = { stopSelf() },
            )
        val shortcut =
            ScheduleShortcutOverlay(
                this,
                onScheduleClick = ::onScheduleScreenshotClick,
                onPermissionRevoked = { stopSelf() },
            )
        try {
            task.attach()
            shortcut.attach()
            taskOverlay = task
            shortcutOverlay = shortcut
            task.setCaptureHidden(captureMutex.isLocked)
            shortcut.setCaptureHidden(captureMutex.isLocked)
            scope.launch { PhoneTaskService.status.collect(task::render) }
        } catch (error: RuntimeException) {
            Log.w("enco.device", "无法打开快捷悬浮球与任务状态", error)
            task.close()
            shortcut.close()
            taskOverlay = null
            shortcutOverlay = null
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        taskOverlay?.close()
        shortcutOverlay?.close()
        taskOverlay = null
        shortcutOverlay = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        shortcutOverlay?.onConfigurationChanged()
    }

    private fun onScheduleScreenshotClick() {
        Toast.makeText(this, R.string.schedule_shortcut_reserved, Toast.LENGTH_SHORT).show()
    }

    private fun openEnco() {
        try {
            startActivity(encoIntent())
        } catch (error: RuntimeException) {
            Log.w("enco.device", "无法从悬浮球打开 enco", error)
            Toast.makeText(this, R.string.phone_task_overlay_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun encoIntent() =
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun notification(): Notification {
        val title = getString(R.string.phone_task_overlay_notification)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_LOW))
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(title)
            .setContentIntent(PendingIntent.getActivity(this, 0, encoIntent(), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "phone_task_overlay"
        private const val NOTIFICATION = 3
        private var taskOverlay: PhoneTaskOverlay? = null
        private var shortcutOverlay: ScheduleShortcutOverlay? = null
        private val captureMutex = Mutex()
        internal val isVisible: Boolean get() = taskOverlay?.isVisible == true
        private val anyOverlayVisible: Boolean
            get() = taskOverlay?.isVisible == true || shortcutOverlay?.isVisible == true

        internal fun startIfAllowed(context: Context) {
            if (!Settings.canDrawOverlays(context) || taskOverlay != null || shortcutOverlay != null) return
            try {
                context.startForegroundService(Intent(context, PhoneTaskOverlayService::class.java))
            } catch (error: RuntimeException) {
                Log.w("enco.device", "系统未能启动悬浮球", error)
            }
        }

        internal suspend fun <T> withoutOverlay(action: suspend () -> T): T =
            captureMutex.withLock {
                val wasVisible = anyOverlayVisible
                taskOverlay?.setCaptureHidden(true)
                shortcutOverlay?.setCaptureHidden(true)
                try {
                    // 等两个悬浮窗退出合成，再截图或注入触摸。
                    if (wasVisible) delay(200)
                    action()
                } finally {
                    taskOverlay?.setCaptureHidden(false)
                    shortcutOverlay?.setCaptureHidden(false)
                }
            }
    }
}
