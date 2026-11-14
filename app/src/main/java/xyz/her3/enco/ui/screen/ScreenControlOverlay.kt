package xyz.her3.enco.ui.screen

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.her3.enco.R
import xyz.her3.enco.device.overlay.OverlayContentOwner
import xyz.her3.enco.device.shell.IEncoShellService
import xyz.her3.enco.device.shell.ScreenFrame
import xyz.her3.enco.device.shizuku.ShizukuConnection
import xyz.her3.enco.ui.theme.EncoTheme

internal class ScreenControlOverlay(
    context: Context,
    private val onClose: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val contentOwner = OverlayContentOwner()

    private var frame by mutableStateOf<ScreenFrame?>(null)
    private var busy by mutableStateOf(false)
    private var errorMessage by mutableStateOf<Int?>(null)
    private val params =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.END }
    private val view = ComposeView(context)

    fun attach() {
        contentOwner.attach(view)
        view.setContent {
            EncoTheme {
                ScreenControlPanel(
                    frame = frame,
                    busy = busy,
                    errorMessage = errorMessage,
                    onCapture = { runOperation() },
                    onBack = { runOperation(IEncoShellService::pressBack) },
                    onHome = { runOperation(IEncoShellService::pressHome) },
                    onTouch = { id, fromX, fromY, toX, toY, swipe ->
                        runOperation { service ->
                            if (swipe) service.swipe(id, fromX, fromY, toX, toY) else service.tap(id, fromX, fromY)
                        }
                    },
                    onClose = onClose,
                )
            }
        }
        windowManager.addView(view, params)
    }

    private fun runOperation(input: ((IEncoShellService) -> Boolean)? = null) {
        if (busy) return
        busy = true
        frame = null
        errorMessage = null
        scope.launch {
            try {
                setHidden(true)
                delay(150)
                if (input != null) {
                    val applied = ShizukuConnection.withScreenService(input)
                    if (!applied) {
                        errorMessage = R.string.screen_control_input_failed
                        return@launch
                    }
                    delay(750)
                }
                frame = ShizukuConnection.withScreenService { it.captureScreen() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("enco.device", "主屏操控失败", error)
                errorMessage = R.string.screen_control_failed
            } finally {
                if (view.isAttachedToWindow) setHidden(false)
                busy = false
            }
        }
    }

    private fun setHidden(hidden: Boolean) {
        params.alpha = if (hidden) 0f else 1f
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (hidden) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowManager.updateViewLayout(view, params)
    }

    fun close() {
        scope.cancel()
        if (view.isAttachedToWindow) windowManager.removeViewImmediate(view)
        contentOwner.close()
    }
}
