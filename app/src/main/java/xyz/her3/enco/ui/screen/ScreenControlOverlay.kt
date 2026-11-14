package xyz.her3.enco.ui.screen

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
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
import xyz.her3.enco.device.overlay.PhoneTaskOverlayService
import xyz.her3.enco.device.shizuku.ShizukuConnection
import xyz.her3.enco.ui.theme.EncoTheme

internal class ScreenControlOverlay(
    private val context: Context,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val contentOwner = OverlayContentOwner()

    private var busy by mutableStateOf(false)
    private val params =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                y = (64 * context.resources.displayMetrics.density).toInt()
                setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            }
    private val view = ComposeView(context)

    fun attach() {
        contentOwner.attach(view)
        view.setContent {
            EncoTheme {
                ScreenControlPanel(
                    busy = busy,
                    onCapture = ::captureScreen,
                    resultOptions = emptyList(),
                    onResultOptionSelected = {},
                )
            }
        }
        windowManager.addView(view, params)
    }

    private fun captureScreen() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                setHidden(true)
                delay(150)
                PhoneTaskOverlayService.withoutOverlay {
                    val frame = ShizukuConnection.withScreenService { it.captureScreen() }
                    check(frame.image != null) { "截图未返回图像" }
                }
                Toast.makeText(context, R.string.screen_control_capture_ai_pending, Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("enco.device", "主屏截图失败", error)
                Toast.makeText(context, R.string.screen_control_failed, Toast.LENGTH_LONG).show()
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
