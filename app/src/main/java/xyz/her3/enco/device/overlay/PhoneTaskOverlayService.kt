package xyz.her3.enco.device.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        if (overlay != null) return START_STICKY
        startForeground(NOTIFICATION, notification())
        val ball =
            PhoneTaskOverlay(
                this,
                onOpen = ::openEnco,
                onStop = {
                    if (PhoneTaskService.status.value.running) {
                        stopService(Intent(this, PhoneTaskService::class.java))
                    } else {
                        PhoneTaskService.dismissDraft()
                    }
                },
                onPermissionRevoked = { stopSelf() },
            )
        try {
            ball.attach()
            overlay = ball
            ball.setCaptureHidden(captureMutex.isLocked)
            scope.launch { PhoneTaskService.status.collect(ball::render) }
        } catch (error: RuntimeException) {
            Log.w("enco.device", "无法打开常驻悬浮球", error)
            ball.close()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        overlay?.close()
        overlay = null
        super.onDestroy()
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
        private var overlay: PhoneTaskOverlay? = null
        private val captureMutex = Mutex()
        internal val isVisible: Boolean get() = overlay?.isVisible == true

        internal fun startIfAllowed(context: Context) {
            if (!Settings.canDrawOverlays(context) || overlay != null) return
            try {
                context.startForegroundService(Intent(context, PhoneTaskOverlayService::class.java))
            } catch (error: RuntimeException) {
                Log.w("enco.device", "系统未能启动常驻悬浮球", error)
            }
        }

        internal suspend fun <T> withoutOverlay(action: suspend () -> T): T =
            captureMutex.withLock {
                val wasVisible = isVisible
                overlay?.setCaptureHidden(true)
                try {
                    // 等悬浮球退出合成，再截图或注入触摸。
                    if (wasVisible) delay(100)
                    action()
                } finally {
                    overlay?.setCaptureHidden(false)
                }
            }
    }
}

class RestorePhoneTaskOverlayReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            PhoneTaskOverlayService.startIfAllowed(context)
        }
    }
}
