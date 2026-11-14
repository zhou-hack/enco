package xyz.her3.enco.device.stage

import android.graphics.Bitmap
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.her3.enco.core.api.Action
import xyz.her3.enco.core.api.ActionResult
import xyz.her3.enco.core.api.Capability
import xyz.her3.enco.core.api.DeviceController
import xyz.her3.enco.core.api.IntRect
import xyz.her3.enco.core.api.Observation
import xyz.her3.enco.core.api.RawNode
import xyz.her3.enco.core.api.ScreenElement
import xyz.her3.enco.core.api.Screenshot
import xyz.her3.enco.core.api.Stage
import xyz.her3.enco.core.api.StageKind
import xyz.her3.enco.core.api.StageRequest
import xyz.her3.enco.core.perception.ElementCompressor
import xyz.her3.enco.device.shell.IEncoShellService
import xyz.her3.enco.device.shell.ShellFrame
import xyz.her3.enco.device.shell.ShellNode
import java.io.ByteArrayOutputStream

/**
 * 主屏舞台：把 shell 采集的控件树压缩为编号元素，并把通用动作翻译回固定 AIDL。
 * 仅支持主屏；小窗与虚拟屏由后续实现。现有设置/微信专用链路不受影响。
 */
internal class MainScreenDevice(
    private val service: IEncoShellService,
) : DeviceController {
    private var last: Observation? = null

    override val capabilities: Set<Capability> =
        setOf(
            Capability.MAIN_SCREEN,
            Capability.OBSERVE,
            Capability.SCREENSHOT,
            Capability.GESTURE,
            Capability.TEXT_INPUT,
            Capability.LAUNCH_APP,
        )

    override suspend fun open(stage: StageRequest): Stage {
        require(stage.kind == StageKind.MAIN) { "主屏舞台不支持该执行位置" }
        return Stage(StageKind.MAIN, id = "main")
    }

    override suspend fun observe(): Observation? =
        withContext(Dispatchers.IO) {
            val frame = service.observeMainScreen()
            val rawNodes = frame.nodes.map(ShellNode::toRawNode)
            val compressed = ElementCompressor.compress(rawNodes, frame.width, frame.height)
            Observation(
                id = frame.id,
                elements = compressed.elements,
                requiresTakeover = compressed.requiresTakeover,
                rawNodes = rawNodes,
                screenshot = frame.image?.toScreenshot(),
                packageName = frame.packageName,
            ).also { last = it }
        }

    override suspend fun perform(
        observation: Observation,
        action: Action,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { execute(observation, action) }.getOrElse { false }
        }

    override suspend fun perform(action: Action): ActionResult {
        val observation = last ?: observe() ?: return ActionResult(success = false, message = "无可用画面")
        val ok = perform(observation, action)
        return ActionResult(success = ok, message = if (ok) null else "动作未执行")
    }

    override suspend fun launchApp(query: String): ActionResult = perform(Action.Launch(query))

    private fun execute(
        observation: Observation,
        action: Action,
    ): Boolean {
        val frameId = observation.id
        return when (action) {
            is Action.Tap -> {
                center(observation, action.target)?.let { (x, y) -> service.tap(frameId, x, y) } ?: false
            }

            is Action.TapAt -> {
                service.tap(frameId, action.x, action.y)
            }

            is Action.LongPress -> {
                center(observation, action.target)?.let { (x, y) -> service.longPress(frameId, x, y) } ?: false
            }

            is Action.LongPressAt -> {
                service.longPress(frameId, action.x, action.y)
            }

            is Action.Type -> {
                val path = observation.elements.firstOrNull { it.id == action.target }?.editPath ?: return false
                val applied = service.setText(frameId, path.toIntArray(), action.text)
                if (applied && action.submit) service.pressKey(KeyEvent.KEYCODE_ENTER) else applied
            }

            is Action.Scroll -> {
                val (x, y) = center(observation, action.target) ?: return false
                if (action.forward) {
                    service.swipe(frameId, x, (y + SCROLL_DISTANCE).coerceIn(0, 1000), x, (y - SCROLL_DISTANCE).coerceIn(0, 1000))
                } else {
                    service.swipe(frameId, x, (y - SCROLL_DISTANCE).coerceIn(0, 1000), x, (y + SCROLL_DISTANCE).coerceIn(0, 1000))
                }
            }

            is Action.Swipe -> {
                val (x, y) = action.target?.let { center(observation, it) } ?: (SCREEN_CENTER to SCREEN_CENTER)
                val half = action.distance / 2
                val (from, to) =
                    when (action.direction) {
                        "up" -> (y + half).coerceIn(0, 1000) to (y - half).coerceIn(0, 1000)
                        "down" -> (y - half).coerceIn(0, 1000) to (y + half).coerceIn(0, 1000)
                        else -> null
                    } ?: return false
                if (action.direction == "left" || action.direction == "right") {
                    val (fromX, toX) =
                        if (action.direction == "left") {
                            (x + half).coerceIn(0, 1000) to (x - half).coerceIn(0, 1000)
                        } else {
                            (x - half).coerceIn(0, 1000) to (x + half).coerceIn(0, 1000)
                        }
                    service.swipe(frameId, fromX, y, toX, y)
                } else {
                    service.swipe(frameId, x, from, x, to)
                }
            }

            Action.Back -> {
                service.pressBack()
            }

            Action.Home -> {
                service.pressHome()
            }

            is Action.Launch -> {
                launch(action.app)
            }

            is Action.Wait -> {
                Thread.sleep(action.ms.toLong())
                true
            }

            // Agent 语义动作不由设备执行。
            is Action.AskUser,
            is Action.Remember,
            is Action.TakeOverRequest,
            is Action.Finish,
            Action.TakeOver,
            -> {
                false
            }
        }
    }

    private fun launch(app: String): Boolean {
        if (app.contains('.')) return service.launchPackage(app)
        return when (app.trim().lowercase()) {
            "settings", "设置" -> service.launchApp(IEncoShellService.APP_SETTINGS)
            "wechat", "微信" -> service.launchApp(IEncoShellService.APP_WECHAT)
            "gmail" -> service.launchApp(IEncoShellService.APP_GMAIL)
            else -> false
        }
    }

    private fun center(
        observation: Observation,
        target: Int,
    ): Pair<Int, Int>? {
        val bounds = observation.elements.firstOrNull { it.id == target }?.bounds ?: return null
        return (bounds.left + bounds.right) / 2 to (bounds.top + bounds.bottom) / 2
    }

    private companion object {
        const val SCROLL_DISTANCE = 300
        const val SCREEN_CENTER = 500
    }
}

private fun ShellNode.toRawNode(): RawNode =
    RawNode(
        nodePath = nodePath.toList(),
        bounds = IntRect(left, top, right, bottom),
        packageName = packageName,
        className = className,
        text = text,
        contentDescription = contentDescription,
        hintText = hintText,
        visible = visible,
        enabled = enabled,
        canTap = canTap,
        canLongPress = canLongPress,
        password = password,
        canScrollForward = canScrollForward,
        canScrollBackward = canScrollBackward,
        canEdit = canEdit,
    )

private fun Bitmap.toScreenshot(): Screenshot {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, 80, stream)
    return Screenshot(bytes = stream.toByteArray(), width = width, height = height, mimeType = "image/jpeg")
}
