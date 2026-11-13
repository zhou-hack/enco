package xyz.her3.enco.ui.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import xyz.her3.enco.R

class ScreenControlService : Service() {
    private var overlay: ScreenControlOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_CLOSE) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlay != null) return START_NOT_STICKY

        try {
            startForeground(NOTIFICATION_ID, createNotification())
            val panel = ScreenControlOverlay(this) { stopSelf() }
            overlay = panel
            panel.attach()
        } catch (error: RuntimeException) {
            Log.w("enco.device", "无法启动主屏操控面板", error)
            Toast.makeText(this, R.string.screen_control_open_failed, Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        overlay?.close()
        overlay = null
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val channelName = getString(R.string.screen_control_notification)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, channelName, NotificationManager.IMPORTANCE_LOW),
        )
        val closeIntent = Intent(this, ScreenControlService::class.java).setAction(ACTION_CLOSE)
        val closeAction = PendingIntent.getService(this, 0, closeIntent, PendingIntent.FLAG_IMMUTABLE)
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(channelName)
            .setContentIntent(closeAction)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.screen_control_close), closeAction).build(),
            ).build()
    }

    private companion object {
        const val CHANNEL = "screen_control"
        const val NOTIFICATION_ID = 1
        const val ACTION_CLOSE = "xyz.her3.enco.action.CLOSE_SCREEN_CONTROL"
    }
}
