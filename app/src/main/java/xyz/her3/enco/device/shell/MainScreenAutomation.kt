package xyz.her3.enco.device.shell

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Point
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.roundToInt

internal class MainScreenAutomation {
    fun readGmailInbox(count: Int): Array<String>? = useAutomation { GmailInboxReader(it).read(count) }

    private data class Target(
        val size: Point,
        val rotation: Int,
        val windowPackage: String?,
        val windowClass: String?,
    )

    private var frameId = 0L
    private var target: Target? = null
    private var settingsId = 0L
    private var settingsEntries: List<SettingsTree.Entry> = emptyList()

    fun observeSettings(): SettingsFrame? {
        settingsEntries = emptyList()
        return useSettingsRoot { root ->
            val tree = SettingsTree(root)
            if (!tree.blocked) settingsEntries = tree.entries
            SettingsFrame().apply {
                id = ++settingsId
                requiresTakeover = tree.blocked
                elements =
                    settingsEntries
                        .mapIndexed { index, entry ->
                            SettingsElement().apply {
                                this.id = index + 1
                                label = entry.label
                                canTap = entry.tap
                                canScrollForward = entry.forward
                                canScrollBackward = entry.backward
                            }
                        }.toTypedArray()
            }
        }
    }

    fun performSettingsAction(
        id: Long,
        element: Int,
        command: Int,
    ): Boolean {
        if (id != settingsId) return false
        val expected = settingsEntries
        settingsEntries = emptyList()
        val entry = expected.getOrNull(element - 1) ?: return false
        return useSettingsRoot { root ->
            val current = SettingsTree(root)
            current.entries == expected && current.perform(entry, command)
        } ?: false
    }

    @Suppress("DEPRECATION")
    private fun <T> useSettingsRoot(action: (AccessibilityNodeInfo) -> T): T? =
        useAutomation { automation ->
            val root = awaitRoot(automation) ?: return@useAutomation null
            try {
                if (root.packageName?.toString() == SETTINGS_PACKAGE) action(root) else null
            } finally {
                root.recycle()
            }
        }

    fun capture(): ScreenFrame =
        useAutomation { automation ->
            target = null
            val current = awaitTarget(automation)
            val bitmap = checkNotNull(automation.takeScreenshot()) { "主屏截图失败" }
            val after = awaitTarget(automation)
            val screenshotRatio = bitmap.width.toFloat() / bitmap.height
            val displayRatio = current.size.x.toFloat() / current.size.y
            check(current == after && abs(screenshotRatio - displayRatio) < 0.01f) {
                val width = bitmap.width
                val height = bitmap.height
                bitmap.recycle()
                "截图与主屏状态不一致：$width×$height，$current → $after"
            }
            val preview = scalePreview(bitmap)
            target = current
            ScreenFrame().apply {
                id = ++frameId
                image = preview
                packageName = current.windowPackage
            }
        }

    fun touch(
        id: Long,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        swipe: Boolean,
    ): Boolean {
        require(listOf(fromX, fromY, toX, toY).all { it in 0..1000 })
        val expected = target
        target = null
        if (id != frameId || expected == null) return false

        return useAutomation { automation ->
            val current = awaitTarget(automation)
            if (expected.windowPackage == null || expected != current) {
                Log.i("enco.device", "主屏窗口已变化，取消触摸注入")
                return@useAutomation false
            }
            val startX = toScreen(fromX, expected.size.x)
            val startY = toScreen(fromY, expected.size.y)
            val endX = toScreen(toX, expected.size.x)
            val endY = toScreen(toY, expected.size.y)
            val downTime = SystemClock.uptimeMillis()
            if (!sendTouch(automation, downTime, MotionEvent.ACTION_DOWN, startX, startY)) return@useAutomation false

            var completed = false
            var currentX = startX
            var currentY = startY
            try {
                if (swipe) {
                    repeat(SWIPE_STEPS) { step ->
                        val fraction = (step + 1f) / SWIPE_STEPS
                        currentX = startX + (endX - startX) * fraction
                        currentY = startY + (endY - startY) * fraction
                        SystemClock.sleep(SWIPE_STEP_MS)
                        if (!sendTouch(automation, downTime, MotionEvent.ACTION_MOVE, currentX, currentY)) {
                            return@useAutomation false
                        }
                    }
                }
                completed = sendTouch(automation, downTime, MotionEvent.ACTION_UP, currentX, currentY)
                completed
            } finally {
                if (!completed) sendTouch(automation, downTime, MotionEvent.ACTION_CANCEL, currentX, currentY)
            }
        }
    }

    fun pressKey(key: Int): Boolean =
        useAutomation { automation ->
            target = null
            val time = SystemClock.uptimeMillis()
            val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0)
            val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
            val pressed = automation.injectInputEvent(down, true)
            automation.injectInputEvent(up, true) && pressed
        }

    @Suppress("DEPRECATION")
    fun pasteWeChatText(
        id: Long,
        text: String,
    ): Boolean {
        if (text.length !in 1..500 || text.any { it.isISOControl() && it != '\n' }) return false
        val expected = target
        target = null
        if (id != frameId || expected?.windowPackage != "com.tencent.mm") return false
        return useAutomation { automation ->
            if (awaitTarget(automation) != expected) return@useAutomation false
            val root = awaitRoot(automation) ?: return@useAutomation false
            val field =
                try {
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } finally {
                    root.recycle()
                }
            try {
                if (field?.isPassword == true) return@useAutomation false
                val editable = field?.takeIf { it.packageName?.toString() == expected.windowPackage && it.isEditable }
                if (editable != null && !editable.text.isNullOrEmpty() && !editable.isShowingHintText) return@useAutomation false
                pasteFromClipboard(automation, text, editable) { awaitTarget(automation) == expected }
            } finally {
                field?.recycle()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun awaitTarget(automation: UiAutomation): Target {
        val root = awaitRoot(automation)
        val windowPackage = root?.packageName?.toString()
        val windowClass = root?.className?.toString()
        root?.recycle()

        val displayManagerType = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val manager = displayManagerType.getMethod("getInstance").invoke(null)
        val display =
            displayManagerType
                .getMethod("getRealDisplay", Int::class.javaPrimitiveType)
                .invoke(manager, Display.DEFAULT_DISPLAY) as Display
        val size = Point().also(display::getRealSize)
        return Target(size, display.rotation, windowPackage, windowClass)
    }

    private fun awaitRoot(automation: UiAutomation): AccessibilityNodeInfo? {
        // UiAutomation 刚连接时，活动窗口可能还没同步。
        val deadline = SystemClock.uptimeMillis() + 500
        var root = automation.rootInActiveWindow
        while (root == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(30)
            root = automation.rootInActiveWindow
        }
        return root
    }

    private fun <T> useAutomation(action: (UiAutomation) -> T): T {
        val connectionType = Class.forName("android.app.IUiAutomationConnection")
        val connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance()
        val automation =
            UiAutomation::class.java
                .getConstructor(Looper::class.java, connectionType)
                .newInstance(Looper.getMainLooper(), connection)
        try {
            UiAutomation::class.java
                .getMethod("connect", Int::class.javaPrimitiveType)
                .invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            return action(automation)
        } catch (error: Exception) {
            Log.w("enco.device", "主屏操作失败", error)
            throw IllegalStateException("主屏操作失败", error)
        } finally {
            try {
                UiAutomation::class.java.getMethod("destroy").invoke(automation)
            } catch (error: ReflectiveOperationException) {
                Log.w("enco.device", "释放 UiAutomation 失败", error)
            }
        }
    }

    private fun scalePreview(bitmap: Bitmap): Bitmap {
        val scale = PREVIEW_MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1) return bitmap
        try {
            return Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun sendTouch(
        automation: UiAutomation,
        downTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): Boolean {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            return automation.injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
    }

    private fun toScreen(
        value: Int,
        size: Int,
    ): Float = value * (size - 1) / 1000f

    private companion object {
        const val PREVIEW_MAX_SIDE = 1280
        const val SWIPE_STEPS = 12
        const val SWIPE_STEP_MS = 20L
    }
}
