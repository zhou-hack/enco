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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.her3.enco.R
import xyz.her3.enco.device.shizuku.ShizukuConnection
import xyz.her3.enco.device.shizuku.ShizukuStatus
import xyz.her3.enco.ui.screen.ScreenControlService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                val status by ShizukuConnection.status.collectAsState()
                EncoWelcome(
                    status = status,
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
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null)))
            }
        } catch (error: RuntimeException) {
            Log.w("enco.device", "无法打开主屏操控", error)
            Toast.makeText(this, R.string.screen_control_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuConnection.refresh()
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

@Composable
private fun EncoWelcome(
    status: ShizukuStatus,
    onOpenShizuku: () -> Unit,
    onRetry: () -> Unit,
    onOpenScreenControl: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = stringResource(R.string.app_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                ShizukuCard(status, onOpenShizuku, onRetry)
                if (status == ShizukuStatus.CONNECTED) {
                    Button(onClick = onOpenScreenControl) { Text(stringResource(R.string.screen_control_open)) }
                    Text(
                        stringResource(R.string.screen_control_permission),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShizukuCard(
    status: ShizukuStatus,
    onOpenShizuku: () -> Unit,
    onRetry: () -> Unit,
) {
    val message =
        when (status) {
            ShizukuStatus.CHECKING -> R.string.shizuku_checking
            ShizukuStatus.STOPPED -> R.string.shizuku_stopped
            ShizukuStatus.UPDATE_REQUIRED -> R.string.shizuku_update_required
            ShizukuStatus.NEED_PERMISSION -> R.string.shizuku_needs_permission
            ShizukuStatus.REQUESTING_PERMISSION -> R.string.shizuku_requesting_permission
            ShizukuStatus.MANAGE_PERMISSION -> R.string.shizuku_manage_permission
            ShizukuStatus.CONNECTING -> R.string.shizuku_connecting
            ShizukuStatus.CONNECTED -> R.string.shizuku_connected
            ShizukuStatus.CONNECTION_FAILED -> R.string.shizuku_connection_failed
        }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.shizuku_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
            when (status) {
                ShizukuStatus.STOPPED,
                ShizukuStatus.UPDATE_REQUIRED,
                ShizukuStatus.MANAGE_PERMISSION,
                -> {
                    Button(onClick = onOpenShizuku) { Text(stringResource(R.string.shizuku_open)) }
                }

                ShizukuStatus.NEED_PERMISSION -> {
                    Button(onClick = onRetry) { Text(stringResource(R.string.shizuku_grant)) }
                }

                ShizukuStatus.CONNECTION_FAILED -> {
                    Button(onClick = onRetry) { Text(stringResource(R.string.shizuku_retry)) }
                }

                else -> {}
            }
        }
    }
}
