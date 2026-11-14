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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import xyz.her3.enco.R
import xyz.her3.enco.ui.ControlIcon
import xyz.her3.enco.ui.ControlSymbol
import xyz.her3.enco.ui.theme.EncoTheme
import xyz.her3.enco.ui.theme.LocalEncoColors
import kotlin.math.roundToInt

internal class ScheduleShortcutOverlay(
    private val context: Context,
    private val onScheduleClick: () -> Unit,
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
    private var dock = OverlayDock(OverlayDockSide.RIGHT, 0.33f)
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                setFitInsetsTypes(0)
                title = context.getString(R.string.schedule_shortcut_overlay_title)
            }

    fun attach() {
        contentOwner.attach(container)
        container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        container.visibility = View.INVISIBLE
        container.addOnLayoutChangeListener { _, _, _, _, _, oldLeft, oldTop, oldRight, oldBottom ->
            val sizeChanged = container.width != oldRight - oldLeft || container.height != oldBottom - oldTop
            if (sizeChanged && container.isAttachedToWindow) {
                if (dragging) moveWindow(lastRawX, lastRawY) else placeDock(animated = false)
            }
        }
        view.setContent {
            EncoTheme {
                ScheduleShortcutBall(onScheduleClick)
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

    private fun refreshVisibility() {
        if (!container.isAttachedToWindow) return
        val unlocked = power.isInteractive && !keyguard.isKeyguardLocked
        val show = !capturing && unlocked && Settings.canDrawOverlays(context)
        if (!show) cancelGesture()
        container.visibility = if (show) View.VISIBLE else View.INVISIBLE
        val alpha = if (show) 1f else 0f
        if (params.alpha != alpha) {
            params.alpha = alpha
            windowManager.updateViewLayout(container, params)
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
                Log.w("enco.device", "日程快捷球窗口已被系统移除", error)
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

                else -> {
                    dragging
                }
            }
    }
}

@Composable
private fun ScheduleShortcutBall(onClick: () -> Unit) {
    val colors = LocalEncoColors.current
    val description = stringResource(R.string.schedule_shortcut_capture)
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.accentTint)
            .border(1.dp, colors.accent.copy(alpha = 0.45f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        ControlIcon(ControlSymbol.CAPTURE, Modifier.size(20.dp), colors.accent)
    }
}
