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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
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
import xyz.her3.enco.device.service.PhoneTaskStatus
import xyz.her3.enco.ui.ControlIcon
import xyz.her3.enco.ui.ControlSymbol
import xyz.her3.enco.ui.statusText
import xyz.her3.enco.ui.theme.EncoText
import xyz.her3.enco.ui.theme.EncoTheme
import xyz.her3.enco.ui.theme.LocalEncoColors

internal class PhoneTaskOverlay(
    private val context: Context,
    private val onOpen: () -> Unit,
    private val onOpenResult: (String) -> Unit,
    private val onStop: () -> Unit,
    private val onDismiss: () -> Unit,
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
    private var visible by mutableStateOf(false)
    private var dismissed = false
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
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && !status.running && status.message != R.string.phone_task_idle) {
                onDismiss()
            }
            false
        }
        view.setContent {
            EncoTheme {
                if (visible) {
                    if (status.completed) {
                        val id = status.taskId
                        val openResult = if (status.canViewResult && id != null) ({ onOpenResult(id) }) else null
                        CompletedTaskPill(openResult, onDismiss)
                    } else {
                        PhoneTaskIsland(
                            status = status,
                            onOpen = {
                                val id = status.taskId
                                if (status.canViewResult && id != null) onOpenResult(id) else onOpen()
                            },
                            onStop = onStop,
                            onDismiss = onDismiss,
                        )
                    }
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
        if (status.running || status.taskId != this.status.taskId || (status.canViewResult && !this.status.canViewResult)) {
            dismissed = false
        }
        this.status = status
        refreshVisibility()
    }

    fun dismissResult(taskId: String?) {
        if (status.running || taskId != status.taskId) return
        dismissed = true
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
        val show =
            status.message != R.string.phone_task_idle &&
                !dismissed &&
                !capturing &&
                unlocked &&
                Settings.canDrawOverlays(context)
        visible = show
        view.visibility = if (show) View.VISIBLE else View.INVISIBLE
        val alpha = if (show) 1f else 0f
        if (params.alpha != alpha) {
            params.alpha = alpha
            windowManager.updateViewLayout(view, params)
        }
    }

    fun close() {
        if (watchingPermission) appOps.stopWatchingMode(permissionChanged)
        main.removeCallbacksAndMessages(null)
        if (receiverRegistered) context.unregisterReceiver(screenEvents)
        if (view.isAttachedToWindow) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (error: IllegalArgumentException) {
                Log.w("enco.device", "任务状态窗口已被系统移除", error)
            }
        }
        contentOwner.close()
    }
}

@Composable
private fun CompletedTaskPill(
    onOpenResult: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = LocalEncoColors.current
    val green = colors.positive
    val orbit =
        rememberInfiniteTransition(label = "completionBorder").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
            label = "completionHighlight",
        )
    Row(
        modifier =
            Modifier
                .widthIn(min = 112.dp)
                .height(42.dp)
                .clip(CircleShape)
                .background(colors.panel)
                .drawWithCache {
                    val inset = 1.5.dp.toPx()
                    val outline =
                        Path().apply {
                            addRoundRect(
                                RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(size.height / 2 - inset)),
                            )
                        }
                    val measure = PathMeasure().apply { setPath(outline, forceClosed = true) }
                    val highlight = Path()
                    val trackStroke = Stroke(1.dp.toPx())
                    val lightStroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                    onDrawWithContent {
                        drawContent()
                        drawPath(outline, green.copy(alpha = 0.4f), style = trackStroke)
                        val length = measure.length
                        val start = orbit.value * length
                        val end = start + length * 0.24f
                        highlight.reset()
                        measure.getSegment(start, end.coerceAtMost(length), highlight)
                        if (end > length) measure.getSegment(0f, end - length, highlight)
                        drawPath(highlight, green, style = lightStroke)
                    }
                }.clickable(
                    role = Role.Button,
                    onClickLabel =
                        stringResource(
                            if (onOpenResult == null) R.string.phone_task_overlay_dismiss else R.string.phone_task_open_result,
                        ),
                    onClick = onOpenResult ?: onDismiss,
                ).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlIcon(ControlSymbol.CHECK, Modifier.size(16.dp), green)
        EncoText(
            stringResource(if (onOpenResult == null) R.string.phone_task_overlay_completed else R.string.phone_task_open_result),
            Modifier.padding(start = 7.dp),
            color = colors.text,
            size = 13.sp,
            weight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PhoneTaskIsland(
    status: PhoneTaskStatus,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalEncoColors.current
    val waiting = status.review != null
    val accent by animateColorAsState(targetValue = taskAccent(status), animationSpec = tween(180), label = "taskAccent")
    val pulse by
        rememberInfiniteTransition(label = "reviewIndicator").animateFloat(
            initialValue = 0.35f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "reviewPulse",
        )
    val action = stringResource(if (status.running) R.string.phone_task_stop else R.string.phone_task_overlay_dismiss)
    val mainAction =
        when {
            !status.running && status.canViewResult -> onOpen
            !status.running -> onDismiss
            waiting -> onOpen
            else -> null
        }
    val clickLabel =
        when {
            !status.running && status.canViewResult -> stringResource(R.string.phone_task_open_result)
            !status.running -> stringResource(R.string.phone_task_overlay_dismiss)
            waiting -> stringResource(R.string.phone_task_overlay_review)
            else -> null
        }
    val statusWidth = (LocalConfiguration.current.screenWidthDp - 24).coerceIn(200, 320)
    Row(
        modifier =
            Modifier
                .width(statusWidth.dp)
                .height(48.dp)
                .clip(CircleShape)
                .background(colors.panel)
                .border(1.dp, accent.copy(alpha = if (waiting) pulse else 0.48f), CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .then(
                    if (mainAction == null) {
                        Modifier
                    } else {
                        Modifier.clickable(role = Role.Button, onClickLabel = clickLabel, onClick = mainAction)
                    },
                ).height(48.dp)
                .padding(start = 10.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EncoMark(colors.accent, colors.onAccent)
            EncoText(
                if (status.canViewResult) stringResource(R.string.phone_task_open_result) else status.statusText(),
                Modifier
                    .weight(1f)
                    .padding(start = 9.dp),
                size = 13.sp,
                color = colors.text,
                weight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .clickable(role = Role.Button, onClick = if (status.running) onStop else onDismiss)
                .semantics { contentDescription = action },
            contentAlignment = Alignment.Center,
        ) {
            ControlIcon(if (status.running) ControlSymbol.STOP else ControlSymbol.CLOSE, Modifier.size(17.dp), accent)
        }
    }
}

@Composable
private fun taskAccent(status: PhoneTaskStatus): Color {
    val colors = LocalEncoColors.current
    return when {
        status.review != null -> colors.warning
        !status.running && status.message == R.string.phone_task_stopped -> colors.muted
        !status.running -> colors.error
        else -> colors.accent
    }
}

@Composable
private fun EncoMark(
    accent: Color,
    onAccent: Color,
) {
    Box(
        Modifier.size(28.dp).background(accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        EncoText("e", size = 18.sp, color = onAccent, weight = FontWeight.Bold)
    }
}
