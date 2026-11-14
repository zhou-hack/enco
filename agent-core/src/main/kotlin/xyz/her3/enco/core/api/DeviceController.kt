package xyz.her3.enco.core.api

interface DeviceController {
    suspend fun observe(): Observation?

    suspend fun perform(
        observation: Observation,
        action: Action,
    ): Boolean

    suspend fun open(stage: StageRequest): Stage {
        require(stage.kind == StageKind.MAIN) { "当前设备只支持主屏" }
        return Stage(stage.kind)
    }

    suspend fun perform(action: Action): ActionResult {
        val observation = observe() ?: return ActionResult(success = false)
        return ActionResult(success = perform(observation, action))
    }

    suspend fun launchApp(query: String): ActionResult = perform(Action.Launch(query))

    suspend fun close(keepApp: Boolean) = Unit

    val capabilities: Set<Capability>
        get() = emptySet()
}

enum class StageKind {
    MAIN,
    FLOATING_WINDOW,
    VIRTUAL_DISPLAY,
}

data class StageRequest(
    val kind: StageKind = StageKind.MAIN,
)

data class Stage(
    val kind: StageKind,
    val id: String? = null,
)

enum class Capability {
    MAIN_SCREEN,
    FLOATING_WINDOW,
    VIRTUAL_DISPLAY,
    OBSERVE,
    SCREENSHOT,
    GESTURE,
    TEXT_INPUT,
    LAUNCH_APP,
}

data class ActionResult(
    val success: Boolean,
    val message: String? = null,
)

data class Observation(
    val id: Long,
    val elements: List<ScreenElement>,
    val requiresTakeover: Boolean = false,
    val rawNodes: List<RawNode> = emptyList(),
    val screenshot: Screenshot? = null,
    val packageName: String? = null,
)

data class ScreenElement(
    val id: Int,
    val label: String,
    val canTap: Boolean,
    val canScrollForward: Boolean,
    val canScrollBackward: Boolean,
    val bounds: IntRect? = null,
    val nodePath: List<Int>? = null,
    val editPath: List<Int>? = null,
    val canEdit: Boolean = false,
    val canLongPress: Boolean = false,
)

sealed interface Action {
    data class Tap(
        val target: Int,
    ) : Action

    data class TapAt(
        val x: Int,
        val y: Int,
    ) : Action

    data class LongPress(
        val target: Int,
    ) : Action

    data class LongPressAt(
        val x: Int,
        val y: Int,
    ) : Action

    data class Type(
        val target: Int,
        val text: String,
        val submit: Boolean = false,
    ) : Action

    data class Scroll(
        val target: Int,
        val forward: Boolean,
    ) : Action

    data class Swipe(
        val direction: String,
        val distance: Int = 500,
        val target: Int? = null,
    ) : Action

    data object Back : Action

    data object Home : Action

    data class Launch(
        val app: String,
    ) : Action

    data class Wait(
        val ms: Int,
    ) : Action

    data class AskUser(
        val question: String,
    ) : Action

    data class Remember(
        val fact: String,
    ) : Action

    data class TakeOverRequest(
        val reason: String,
    ) : Action

    data class Finish(
        val success: Boolean,
        val summary: String,
    ) : Action

    data object TakeOver : Action
}
