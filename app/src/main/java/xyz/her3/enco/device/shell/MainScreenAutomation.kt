package xyz.her3.enco.device.shell

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import xyz.her3.enco.core.agent.GmailAction
import xyz.her3.enco.core.protocol.encoJson
import kotlin.math.abs
import kotlin.math.roundToInt

internal class MainScreenAutomation {
    private var gmailReader: GmailScreenReader? = null

    fun observeGmail(): String? =
        useGmail { _, root ->
            val reader = GmailScreenReader(root, ++frameId)
            gmailReader = reader
            encoJson.encodeToString(reader.screen)
        }

    @Suppress("DEPRECATION")
    fun actInGmail(
        id: Long,
        control: Int,
        query: String?,
    ): Boolean {
        val previous = gmailReader
        gmailReader = null
        if (id != frameId || previous == null) return false
        val entry = previous.entry(control) ?: return false
        if ((query != null) != (entry.action == GmailAction.SEARCH) ||
            (query != null && (query.isBlank() || query.length > 160 || query.any(Char::isISOControl)))
        ) {
            return false
        }
        return useGmail { automation, root ->
            if (GmailScreenReader(root, id).entry(control) != entry) return@useGmail false
            withNode(root, entry.path) { node ->
                when (entry.action) {
                    GmailAction.TAP, GmailAction.OPEN_MESSAGE -> {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }

                    GmailAction.SCROLL_FORWARD -> {
                        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    }

                    GmailAction.SCROLL_BACKWARD -> {
                        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    }

                    GmailAction.SEARCH -> {
                        val pasted =
                            pasteFromClipboard(automation, checkNotNull(query), node, replaceExisting = true) {
                                isGmailActive(automation)
                            }
                        pasted && isGmailActive(automation) && injectKey(automation, KeyEvent.KEYCODE_ENTER)
                    }
                }
            }
        } ?: false
    }

    @Suppress("DEPRECATION")
    private fun <T> useGmail(action: (UiAutomation, AccessibilityNodeInfo) -> T): T? =
        useAutomation { automation ->
            val info = automation.serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automation.serviceInfo = info
            val root = awaitRoot(automation) ?: return@useAutomation null
            try {
                if (root.packageName?.toString() == GmailScreenReader.PACKAGE) action(automation, root) else null
            } finally {
                root.recycle()
            }
        }

    @Suppress("DEPRECATION")
    private fun isGmailActive(automation: UiAutomation): Boolean {
        val root = awaitRoot(automation) ?: return false
        return try {
            root.packageName?.toString() == GmailScreenReader.PACKAGE
        } finally {
            root.recycle()
        }
    }

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
            val snapshot = captureSnapshot(automation, includeNodes = false)
            ScreenFrame().apply {
                id = ++frameId
                image = snapshot.image
                packageName = snapshot.target.windowPackage
            }
        }

    fun observe(): ShellFrame =
        useAutomation { automation ->
            val snapshot = captureSnapshot(automation, includeNodes = true)
            ShellFrame().apply {
                id = ++frameId
                image = snapshot.image
                packageName = snapshot.target.windowPackage
                width = snapshot.target.size.x
                height = snapshot.target.size.y
                rotation = snapshot.target.rotation
                nodes = snapshot.nodes
            }
        }

    private data class Snapshot(
        val target: Target,
        val image: Bitmap,
        val nodes: Array<ShellNode>,
    )

    private fun captureSnapshot(
        automation: UiAutomation,
        includeNodes: Boolean,
    ): Snapshot {
        target = null
        val current = awaitTarget(automation)
        val nodes = if (includeNodes) readActiveWindow(automation, current) else emptyArray()
        val bitmap = checkNotNull(automation.takeScreenshot()) { "主屏截图失败" }
        try {
            val after = awaitTarget(automation)
            val screenshotRatio = bitmap.width.toFloat() / bitmap.height
            val displayRatio = current.size.x.toFloat() / current.size.y
            check(current == after && abs(screenshotRatio - displayRatio) < 0.01f) {
                "截图与主屏状态不一致：${bitmap.width}×${bitmap.height}，$current → $after"
            }
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        }
        val preview = scalePreview(bitmap)
        target = current
        return Snapshot(current, preview, nodes)
    }

    @Suppress("DEPRECATION")
    private fun readActiveWindow(
        automation: UiAutomation,
        expected: Target,
    ): Array<ShellNode> {
        val root = checkNotNull(awaitRoot(automation)) { "无法读取主屏活动窗口" }
        try {
            check(root.packageName?.toString() == expected.windowPackage && root.className?.toString() == expected.windowClass) {
                "主屏活动窗口与快照状态不一致"
            }
            val nodes = mutableListOf<ShellNode>()
            appendNode(root, emptyList(), nodes)
            return nodes.toTypedArray()
        } finally {
            root.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun appendNode(
        node: AccessibilityNodeInfo,
        path: List<Int>,
        nodes: MutableList<ShellNode>,
    ) {
        if (path.size > MAX_DEPTH || nodes.size >= MAX_NODES) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val actions = node.actionList
        nodes.add(
            ShellNode().apply {
                nodePath = path.toIntArray()
                left = bounds.left
                top = bounds.top
                right = bounds.right
                bottom = bounds.bottom
                packageName = node.packageName?.toString()
                className = node.className?.toString()
                text = if (node.isPassword) null else node.text?.toString()?.take(MAX_TEXT_LENGTH)
                contentDescription = node.contentDescription?.toString()?.take(MAX_TEXT_LENGTH)
                hintText = node.hintText?.toString()?.take(MAX_TEXT_LENGTH)
                visible = node.isVisibleToUser
                enabled = node.isEnabled
                canTap = node.isClickable
                canLongPress = node.isLongClickable
                password = node.isPassword
                canEdit = node.isEditable
                canScrollForward =
                    node.isScrollable && actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                canScrollBackward =
                    node.isScrollable && actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            },
        )
        repeat(node.childCount) { index ->
            if (nodes.size >= MAX_NODES) return
            val child = node.getChild(index) ?: return@repeat
            try {
                appendNode(child, path + index, nodes)
            } finally {
                child.recycle()
            }
        }
    }

    fun longPress(
        id: Long,
        x: Int,
        y: Int,
    ): Boolean = touch(id, x, y, x, y, swipe = false, holdMs = LONG_PRESS_MS)

    fun touch(
        id: Long,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        swipe: Boolean,
        holdMs: Long = 0,
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
            if (holdMs > 0) SystemClock.sleep(holdMs)

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

    fun pressKey(key: Int): Boolean {
        if (key !in ALLOWED_KEYS) return false
        return useAutomation { automation ->
            target = null
            injectKey(automation, key)
        }
    }

    private fun injectKey(
        automation: UiAutomation,
        key: Int,
    ): Boolean {
        val time = SystemClock.uptimeMillis()
        val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0).apply { source = InputDevice.SOURCE_KEYBOARD }
        val pressed = automation.injectInputEvent(down, true)
        return automation.injectInputEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), true) && pressed
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

    fun launchPackage(packageName: String): Boolean {
        target = null
        return ShellAppLauncher.launchPackage(packageName)
    }

    @Suppress("DEPRECATION")
    fun setText(
        id: Long,
        path: IntArray,
        text: String,
    ): Boolean {
        if (text.length !in 1..500 || text.any { it.isISOControl() && it != '\n' }) return false
        val expected = target
        target = null
        if (id != frameId || expected?.windowPackage == null || path.size > MAX_DEPTH) return false
        return useAutomation { automation ->
            if (awaitTarget(automation) != expected) return@useAutomation false
            val root = awaitRoot(automation) ?: return@useAutomation false
            try {
                if (root.packageName?.toString() != expected.windowPackage || root.className?.toString() != expected.windowClass) {
                    return@useAutomation false
                }
                withNode(root, path.toList()) { node ->
                    if (!node.isVisibleToUser || !node.isEnabled || !node.isEditable || node.isPassword || !node.isFocused) {
                        return@withNode false
                    }
                    pasteFromClipboard(automation, text, node, replaceExisting = true) { awaitTarget(automation) == expected }
                }
            } finally {
                root.recycle()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun withNode(
        root: AccessibilityNodeInfo,
        path: List<Int>,
        action: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        var node = root
        try {
            for (index in path) {
                if (index !in 0 until node.childCount) return false
                val child = node.getChild(index) ?: return false
                if (node !== root) node.recycle()
                node = child
            }
            return action(node)
        } finally {
            if (node !== root) node.recycle()
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
        const val MAX_DEPTH = 40
        const val MAX_NODES = 600
        const val MAX_TEXT_LENGTH = 120
        const val LONG_PRESS_MS = 600L
        const val SWIPE_STEPS = 12
        const val SWIPE_STEP_MS = 20L
        val ALLOWED_KEYS = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_ENTER)
    }
}
