package xyz.her3.enco.device.overlay

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import xyz.her3.enco.R
import xyz.her3.enco.device.service.PhoneTaskProgress
import xyz.her3.enco.device.service.PhoneTaskReview
import xyz.her3.enco.device.service.PhoneTaskStatus
import xyz.her3.enco.ui.ControlIcon
import xyz.her3.enco.ui.ControlSymbol
import xyz.her3.enco.ui.phaseText
import xyz.her3.enco.ui.statusText
import xyz.her3.enco.ui.theme.EncoText

internal class PhoneTaskOverlay(
    private val context: Context,
    private val onOpen: () -> Unit,
    private val onStop: () -> Unit,
    private val onPermissionRevoked: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val contentOwner = OverlayContentOwner()
    private val view = ComposeView(context)
    private var status by mutableStateOf(PhoneTaskStatus(R.string.phone_task_idle))
    private var expanded by mutableStateOf(false)
    private var capturing = false
    private var receiverRegistered = false
    private var watchingPermission = false
    private val permissionChanged =
        AppOpsManager.OnOpChangedListener { _, _ ->
            main.post {
                if (Settings.canDrawOverlays(context)) refreshVisibility() else onPermissionRevoked()
            }
        }
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (8 * context.resources.displayMetrics.density).toInt()
                setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                title = context.getString(R.string.phone_task_overlay_title)
            }

    fun attach() {
        contentOwner.attach(view)
        view.visibility = View.INVISIBLE
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) expanded = false
            false
        }
        view.setContent {
            val current = status
            val toggle = { expanded = !expanded }
            if (expanded) {
                PhoneTaskMenu(
                    status = current,
                    onCollapse = { expanded = false },
                    onOpen = {
                        expanded = false
                        onOpen()
                    },
                    onStop = {
                        expanded = false
                        onStop()
                    },
                )
            } else {
                val progress = current.progress
                if (progress == null) {
                    PhoneTaskBall(waiting = current.review != null, onClick = toggle)
                } else {
                    PhoneTaskCapsule(progress, onClick = toggle)
                }
            }
        }
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
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, permissionChanged)
        watchingPermission = true
    }

    fun render(status: PhoneTaskStatus) {
        if (!status.running && this.status.running) expanded = false
        this.status = status
        refreshVisibility()
    }

    fun setCaptureHidden(hidden: Boolean) {
        capturing = hidden
        refreshVisibility()
    }

    val isVisible: Boolean get() = view.isAttachedToWindow && view.visibility == View.VISIBLE

    private fun refreshVisibility() {
        if (!view.isAttachedToWindow) return
        val unlocked = power.isInteractive && !keyguard.isKeyguardLocked
        if (!unlocked) expanded = false
        val show = !capturing && unlocked && Settings.canDrawOverlays(context)
        view.visibility = if (show) View.VISIBLE else View.INVISIBLE
    }

    fun close() {
        if (watchingPermission) appOps.stopWatchingMode(permissionChanged)
        main.removeCallbacksAndMessages(null)
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

private val capsuleBackground = Color(0xFF182338)
private val capsuleText = Color(0xFFF1F5FF)
private val capsuleAccent = Color(0xFFADC2FF)
private val capsuleWaiting = Color(0xFFFFC578)

@Composable
private fun PhoneTaskBall(
    waiting: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (waiting) capsuleWaiting else capsuleAccent
    val label = stringResource(R.string.phone_task_overlay_menu)
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(capsuleBackground)
            .border(1.dp, accent.copy(alpha = .50f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ControlIcon(ControlSymbol.PHONE, Modifier.size(21.dp), accent)
    }
}

@Composable
private fun PhoneTaskCapsule(
    progress: PhoneTaskProgress,
    onClick: () -> Unit,
) {
    val accent = if (progress.waiting) capsuleWaiting else capsuleAccent
    val text = progress.phaseText()
    val app = stringResource(progress.app)
    val open = stringResource(R.string.phone_task_overlay_menu)
    Row(
        modifier =
            Modifier
                .padding(horizontal = 12.dp)
                .widthIn(max = 340.dp)
                .clip(CircleShape)
                .background(capsuleBackground)
                .border(1.dp, accent.copy(alpha = .40f), CircleShape)
                .clickable(role = Role.Button, onClickLabel = open, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(accent, CircleShape))
        EncoText(app, size = 13.sp, color = accent, weight = FontWeight.Bold, maxLines = 1)
        EncoText(text, Modifier.weight(1f, fill = false), 13.sp, capsuleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ControlIcon(ControlSymbol.NEXT, Modifier.size(13.dp), accent)
    }
}

@Composable
private fun PhoneTaskMenu(
    status: PhoneTaskStatus,
    onCollapse: () -> Unit,
    onOpen: () -> Unit,
    onStop: () -> Unit,
) {
    val accent = if (status.review != null) capsuleWaiting else capsuleAccent
    val shape = RoundedCornerShape(20.dp)
    val collapse = stringResource(R.string.phone_task_overlay_collapse)
    Column(
        modifier =
            Modifier
                .padding(horizontal = 12.dp)
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(capsuleBackground)
                .border(1.dp, accent.copy(alpha = .40f), shape)
                .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EncoText(
                status.statusText(),
                Modifier.weight(1f, fill = false).padding(start = 4.dp),
                size = 13.sp,
                color = accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClick = onCollapse)
                    .semantics { contentDescription = collapse },
                contentAlignment = Alignment.Center,
            ) {
                ControlIcon(ControlSymbol.CLOSE, Modifier.size(17.dp), capsuleText)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val openLabel =
                when (status.review) {
                    is PhoneTaskReview.Tap -> R.string.phone_task_overlay_review
                    is PhoneTaskReview.Draft -> R.string.phone_task_draft_title
                    null -> R.string.phone_task_overlay_open
                }
            OverlayAction(openLabel, accent, onOpen)
            if (status.running || status.review != null) {
                OverlayAction(R.string.phone_task_stop, capsuleText, onStop)
            }
        }
    }
}

@Composable
private fun OverlayAction(
    label: Int,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        EncoText(stringResource(label), color = color, size = 13.sp, weight = FontWeight.SemiBold)
    }
}
