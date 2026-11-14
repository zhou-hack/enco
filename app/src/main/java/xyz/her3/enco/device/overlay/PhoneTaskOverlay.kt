package xyz.her3.enco.device.overlay

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import xyz.her3.enco.R
import xyz.her3.enco.device.service.PhoneTaskProgress
import xyz.her3.enco.ui.ControlIcon
import xyz.her3.enco.ui.ControlSymbol
import xyz.her3.enco.ui.phaseText
import xyz.her3.enco.ui.theme.EncoText

internal class PhoneTaskOverlay(
    private val context: Context,
    private val onOpen: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val contentOwner = OverlayContentOwner()
    private val view = ComposeView(context)
    private var progress by mutableStateOf<PhoneTaskProgress?>(null)
    private var activityVisible = true
    private var capturing = false
    private var receiverRegistered = false
    private val screenEvents =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = refreshVisibility()
        }
    private val params =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (8 * context.resources.displayMetrics.density).toInt()
                setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                title = context.getString(R.string.phone_task_title)
            }

    fun attach() {
        contentOwner.attach(view)
        view.visibility = View.INVISIBLE
        view.setContent { progress?.let { PhoneTaskCapsule(it, onOpen, onStop) } }
        windowManager.addView(view, params)
        ContextCompat.registerReceiver(
            context,
            screenEvents,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    fun render(
        progress: PhoneTaskProgress?,
        activityVisible: Boolean,
    ) {
        this.progress = progress
        this.activityVisible = activityVisible
        refreshVisibility()
    }

    suspend fun <T> withoutOverlay(action: suspend () -> T): T {
        val wasVisible = isVisible
        capturing = true
        refreshVisibility()
        return try {
            // 等待窗口消失的那一帧合成后再读屏或注入手势。
            if (wasVisible) delay(100)
            action()
        } finally {
            capturing = false
            refreshVisibility()
        }
    }

    val isVisible: Boolean get() = view.isAttachedToWindow && view.visibility == View.VISIBLE

    private fun refreshVisibility() {
        val show =
            progress != null && !activityVisible && !capturing &&
                power.isInteractive && !keyguard.isKeyguardLocked && Settings.canDrawOverlays(context)
        view.visibility = if (show) View.VISIBLE else View.INVISIBLE
    }

    fun close() {
        if (receiverRegistered) context.unregisterReceiver(screenEvents)
        if (view.isAttachedToWindow) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (error: IllegalArgumentException) {
                Log.w("enco.device", "任务悬浮胶囊窗口已被系统移除", error)
            }
        }
        contentOwner.close()
    }
}

@Composable
private fun PhoneTaskCapsule(
    progress: PhoneTaskProgress,
    onOpen: () -> Unit,
    onStop: () -> Unit,
) {
    val accent = if (progress.waiting) Color(0xFFFFC578) else Color(0xFFADC2FF)
    val text = progress.phaseText()
    val app = stringResource(progress.app)
    val open = stringResource(R.string.phone_task_overlay_review)
    val stop = stringResource(R.string.phone_task_stop)
    Row(
        modifier =
            Modifier
                .padding(horizontal = 12.dp)
                .widthIn(max = 340.dp)
                .clip(CircleShape)
                .background(Color(0xFF182338))
                .border(1.dp, accent.copy(alpha = .40f), CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .then(if (progress.waiting) Modifier.clickable(role = Role.Button, onClickLabel = open, onClick = onOpen) else Modifier)
                    .heightIn(min = 48.dp)
                    .padding(start = 14.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(accent, CircleShape))
            EncoText(app, size = 13.sp, color = accent, weight = FontWeight.Bold, maxLines = 1)
            EncoText(
                text,
                modifier = Modifier.weight(1f, fill = false),
                size = 13.sp,
                color = Color(0xFFF1F5FF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .clickable(role = Role.Button, onClick = onStop)
                .semantics { contentDescription = stop },
            contentAlignment = Alignment.Center,
        ) {
            ControlIcon(ControlSymbol.STOP, Modifier.size(13.dp), Color(0xFFD9E3FC))
        }
    }
}
