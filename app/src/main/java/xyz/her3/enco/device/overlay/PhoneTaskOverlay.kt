package xyz.her3.enco.device.overlay

import android.animation.ValueAnimator
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
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.roundToInt

internal class PhoneTaskOverlay(
    private val context: Context,
    private val onOpen: () -> Unit,
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
    private val container = DragContainer(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeMargin = (8 * context.resources.displayMetrics.density).roundToInt()
    private var status by mutableStateOf(PhoneTaskStatus(R.string.phone_task_idle))
    private var expanded by mutableStateOf(false)
    private var dock by mutableStateOf(OverlayDock(OverlayDockSide.RIGHT, 0.08f))
    private var safeArea = initialSafeArea()
    private var snapAnimator: ValueAnimator? = null
    private var capturing = false
    private var dragging = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var startRawX = 0f
    private var startRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var startWindowX = 0
    private var startWindowY = 0
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
                gravity = Gravity.TOP or Gravity.LEFT
                setFitInsetsTypes(0)
                title = context.getString(R.string.phone_task_overlay_title)
            }

    fun attach() {
        contentOwner.attach(container)
        container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        container.visibility = View.INVISIBLE
        container.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                handleOutsideTouch()
                true
            } else {
                false
            }
        }
        container.addOnLayoutChangeListener { _, _, _, _, _, oldLeft, oldTop, oldRight, oldBottom ->
            val sizeChanged = container.width != oldRight - oldLeft || container.height != oldBottom - oldTop
            if (sizeChanged && container.isAttachedToWindow) {
                if (dragging) {
                    moveWindow(lastRawX, lastRawY)
                } else {
                    placeDock(animated = false)
                }
            }
        }
        view.setContent {
            EncoTheme {
                PhoneTaskOverlayContent(
                    status = status,
                    expanded = expanded,
                    dockSide = dock.side,
                    onExpand = { updateExpanded(true) },
                    onCollapse = { updateExpanded(false) },
                    onOpen = onOpen,
                    onStop = onStop,
                    onDismiss = onDismiss,
                )
            }
        }
        windowManager.addView(container, params)
        container.post {
            refreshSafeArea()
            placeDock(animated = false)
        }
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
        val previous = this.status
        this.status = status
        when {
            status.running && !previous.running -> updateExpanded(status.review != null)
            status.review != null && previous.review == null -> updateExpanded(true)
            !status.running && previous.running -> updateExpanded(true)
        }
        refreshVisibility()
    }

    fun setCaptureHidden(hidden: Boolean) {
        capturing = hidden
        if (hidden) cancelGesture()
        refreshVisibility()
    }

    fun onConfigurationChanged() {
        cancelGesture()
        refreshSafeArea()
        container.post { placeDock(animated = false) }
    }

    val isVisible: Boolean get() = container.isAttachedToWindow && container.visibility == View.VISIBLE

    private fun updateExpanded(value: Boolean) {
        if (expanded == value) return
        snapAnimator?.cancel()
        expanded = value
        container.post { placeDock(animated = false) }
    }

    private fun refreshVisibility() {
        if (!container.isAttachedToWindow) return
        val unlocked = power.isInteractive && !keyguard.isKeyguardLocked
        if (!unlocked && status.review == null) expanded = false
        val show = status.message != R.string.phone_task_idle && !capturing && unlocked && Settings.canDrawOverlays(context)
        if (!show) cancelGesture()
        container.visibility = if (show) View.VISIBLE else View.INVISIBLE
        val alpha = if (show) 1f else 0f
        if (params.alpha != alpha) {
            params.alpha = alpha
            windowManager.updateViewLayout(container, params)
        }
    }

    private fun handleOutsideTouch() {
        if (dragging || activePointerId != MotionEvent.INVALID_POINTER_ID) return
        if (status.running) {
            if (status.review == null) updateExpanded(false)
        } else if (status.message != R.string.phone_task_idle) {
            onDismiss()
        }
    }

    private fun initialSafeArea(): OverlaySafeArea {
        val metrics = context.resources.displayMetrics
        return OverlaySafeArea(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun refreshSafeArea() {
        val metrics = windowManager.currentWindowMetrics
        val insets =
            metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
        safeArea =
            OverlaySafeArea(
                left = metrics.bounds.left + insets.left + edgeMargin,
                top = metrics.bounds.top + insets.top + edgeMargin,
                right = metrics.bounds.right - insets.right - edgeMargin,
                bottom = metrics.bounds.bottom - insets.bottom - edgeMargin,
            )
    }

    private fun beginGesture(event: MotionEvent) {
        snapAnimator?.cancel()
        activePointerId = event.getPointerId(0)
        startRawX = event.rawX
        startRawY = event.rawY
        lastRawX = event.rawX
        lastRawY = event.rawY
        startWindowX = params.x
        startWindowY = params.y
        dragging = false
    }

    private fun continueGesture(event: MotionEvent): Boolean {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) {
            cancelGesture()
            return false
        }
        val rawX = event.getRawX(index)
        val rawY = event.getRawY(index)
        lastRawX = rawX
        lastRawY = rawY
        val dx = rawX - startRawX
        val dy = rawY - startRawY
        if (!dragging && dx * dx + dy * dy <= touchSlop * touchSlop) return false
        dragging = true
        moveWindow(rawX, rawY)
        return true
    }

    private fun moveWindow(
        rawX: Float,
        rawY: Float,
    ) {
        val position =
            PhoneTaskOverlayPlacement.clamp(
                area = safeArea,
                width = container.width,
                height = container.height,
                x = startWindowX + (rawX - startRawX).roundToInt(),
                y = startWindowY + (rawY - startRawY).roundToInt(),
            )
        updatePosition(position)
    }

    private fun finishGesture(animated: Boolean) {
        val wasDragging = dragging
        dragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        if (!wasDragging) return
        dock =
            PhoneTaskOverlayPlacement.release(
                area = safeArea,
                width = container.width,
                height = container.height,
                x = params.x,
                y = params.y,
            )
        placeDock(animated)
    }

    private fun cancelGesture() {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID && !dragging) return
        finishGesture(animated = false)
    }

    private fun placeDock(animated: Boolean) {
        val target =
            PhoneTaskOverlayPlacement.place(
                area = safeArea,
                width = container.width,
                height = container.height,
                dock = dock,
            )
        snapAnimator?.cancel()
        if (!animated || !isVisible) {
            updatePosition(target)
            return
        }
        updatePosition(OverlayOffset(params.x, target.y))
        snapAnimator =
            ValueAnimator.ofInt(params.x, target.x).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    updatePosition(OverlayOffset(it.animatedValue as Int, params.y))
                }
                start()
            }
    }

    private fun updatePosition(position: OverlayOffset) {
        if (params.x == position.x && params.y == position.y) return
        params.x = position.x
        params.y = position.y
        if (container.isAttachedToWindow) windowManager.updateViewLayout(container, params)
    }

    fun close() {
        cancelGesture()
        snapAnimator?.cancel()
        if (watchingPermission) appOps.stopWatchingMode(permissionChanged)
        main.removeCallbacksAndMessages(null)
        if (receiverRegistered) context.unregisterReceiver(screenEvents)
        if (container.isAttachedToWindow) {
            try {
                windowManager.removeViewImmediate(container)
            } catch (error: IllegalArgumentException) {
                Log.w("enco.device", "任务悬浮胶囊窗口已被系统移除", error)
            }
        }
        contentOwner.close()
    }

    private inner class DragContainer(
        context: Context,
    ) : FrameLayout(context) {
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginGesture(event)
                }

                MotionEvent.ACTION_MOVE -> {
                    continueGesture(event)
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) activePointerId = MotionEvent.INVALID_POINTER_ID
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelGesture()
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == activePointerId) finishGesture(animated = false)
                }
            }
            return dragging
        }

        override fun onTouchEvent(event: MotionEvent): Boolean =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    continueGesture(event)
                }

                MotionEvent.ACTION_UP -> {
                    val handled = dragging
                    finishGesture(animated = true)
                    handled
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelGesture()
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == activePointerId) finishGesture(animated = false)
                    true
                }

                MotionEvent.ACTION_OUTSIDE -> {
                    handleOutsideTouch()
                    true
                }

                else -> {
                    dragging
                }
            }
    }
}

@Composable
private fun PhoneTaskOverlayContent(
    status: PhoneTaskStatus,
    expanded: Boolean,
    dockSide: OverlayDockSide,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val showIsland = expanded || status.review != null || !status.running
    val reveal by animateFloatAsState(targetValue = if (showIsland) 1f else 0f, animationSpec = tween(160), label = "overlayReveal")
    val accent by animateColorAsState(targetValue = taskAccent(status), animationSpec = tween(180), label = "overlayAccent")
    val origin = if (dockSide == OverlayDockSide.LEFT) TransformOrigin(0f, 0.5f) else TransformOrigin(1f, 0.5f)

    Box {
        if (reveal < 1f) {
            PhoneTaskBall(
                status = status,
                accent = accent,
                enabled = !showIsland,
                onClick = onExpand,
                modifier =
                    Modifier.graphicsLayer {
                        alpha = 1f - reveal
                        scaleX = 1f - reveal * 0.08f
                        scaleY = 1f - reveal * 0.08f
                        transformOrigin = origin
                    },
            )
        }
        if (reveal > 0f) {
            PhoneTaskIsland(
                status = status,
                accent = accent,
                enabled = showIsland,
                onClick = {
                    when {
                        !status.running -> onDismiss()
                        status.review != null -> onOpen()
                        else -> onCollapse()
                    }
                },
                onStop = onStop,
                modifier =
                    Modifier.graphicsLayer {
                        alpha = reveal
                        scaleX = 0.92f + reveal * 0.08f
                        scaleY = 0.92f + reveal * 0.08f
                        transformOrigin = origin
                    },
            )
        }
    }
}

@Composable
private fun taskAccent(status: PhoneTaskStatus): Color {
    val colors = LocalEncoColors.current
    return when {
        status.review != null -> colors.warning
        !status.running && status.message == R.string.phone_task_finished -> colors.positive
        !status.running && status.message == R.string.phone_task_stopped -> colors.muted
        !status.running -> colors.error
        else -> colors.accent
    }
}

@Composable
private fun PhoneTaskBall(
    status: PhoneTaskStatus,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEncoColors.current
    val action = stringResource(R.string.phone_task_overlay_menu)
    val description = status.statusText()
    val rotation by
        rememberInfiniteTransition(label = "runningIndicator").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
            label = "runningRotation",
        )
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.panel)
            .border(1.dp, colors.line, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = action, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (status.running) {
            Canvas(Modifier.size(42.dp)) {
                val stroke = 1.5.dp.toPx()
                drawArc(
                    color = accent,
                    startAngle = rotation,
                    sweepAngle = 84f,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        EncoMark(colors.accent, colors.onAccent)
    }
}

@Composable
private fun PhoneTaskIsland(
    status: PhoneTaskStatus,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEncoColors.current
    val waiting = status.review != null
    val pulse by
        rememberInfiniteTransition(label = "reviewIndicator").animateFloat(
            initialValue = 0.35f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "reviewPulse",
        )
    val action = stringResource(if (status.running) R.string.phone_task_stop else R.string.phone_task_overlay_dismiss)
    val click =
        stringResource(
            when {
                !status.running -> R.string.phone_task_overlay_dismiss
                waiting -> R.string.phone_task_overlay_review
                else -> R.string.phone_task_overlay_collapse
            },
        )
    val islandWidth = (LocalConfiguration.current.screenWidthDp - 24).coerceIn(200, 320)
    Row(
        modifier =
            modifier
                .width(islandWidth.dp)
                .height(48.dp)
                .clip(CircleShape)
                .background(colors.panel)
                .border(1.dp, accent.copy(alpha = if (waiting) pulse else 0.48f), CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = click, onClick = onClick)
                .height(48.dp)
                .padding(start = 10.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EncoMark(colors.accent, colors.onAccent)
            EncoText(
                if (status.message == R.string.phone_task_finished) {
                    stringResource(R.string.phone_task_overlay_completed)
                } else {
                    status.statusText()
                },
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
                .clickable(enabled = enabled, role = Role.Button, onClick = if (status.running) onStop else onClick)
                .semantics { contentDescription = action },
            contentAlignment = Alignment.Center,
        ) {
            ControlIcon(if (status.running) ControlSymbol.STOP else ControlSymbol.CLOSE, Modifier.size(17.dp), accent)
        }
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
