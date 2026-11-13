package xyz.her3.enco.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.her3.enco.R
import xyz.her3.enco.device.shizuku.ShizukuConnection
import xyz.her3.enco.ui.screen.ScreenControlService
import xyz.her3.enco.ui.theme.EncoTheme

class MainActivity : ComponentActivity() {
    private var overlayAllowed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EncoTheme {
                val status by ShizukuConnection.status.collectAsState()
                EncoHome(
                    status = status,
                    overlayAllowed = overlayAllowed,
                    onOverlaySettings = ::openOverlaySettings,
                    onOpenShizuku = ::openShizuku,
                    onRetry = { ShizukuConnection.refresh(requestPermission = true) },
                    onOpenScreenControl = ::openScreenControl,
                )
            }
        }
    }

    private fun openScreenControl() {
        try {
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, ScreenControlService::class.java))
                moveTaskToBack(true)
            } else {
                openOverlaySettings()
            }
        } catch (error: RuntimeException) {
            Log.w("enco.device", "无法打开主屏操控", error)
            Toast.makeText(this, R.string.screen_control_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        overlayAllowed = Settings.canDrawOverlays(this)
        ShizukuConnection.refresh()
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null)))
        } catch (error: ActivityNotFoundException) {
            Log.w("enco.device", "无法打开悬浮窗权限设置", error)
            Toast.makeText(this, R.string.screen_control_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openShizuku() {
        val launchIntent =
            packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
        try {
            startActivity(launchIntent)
        } catch (error: ActivityNotFoundException) {
            Log.w("enco.shizuku", "无法打开 Shizuku", error)
            Toast.makeText(this, R.string.shizuku_open_failed, Toast.LENGTH_LONG).show()
        }
    }
}
