package xyz.her3.enco.core.api

interface DeviceController {
    suspend fun observe(): Observation?

    suspend fun perform(
        observation: Observation,
        action: Action,
    ): Boolean
}

data class Observation(
    val id: Long,
    val elements: List<ScreenElement>,
    val requiresTakeover: Boolean = false,
)

data class ScreenElement(
    val id: Int,
    val label: String,
    val canTap: Boolean,
    val canScrollForward: Boolean,
    val canScrollBackward: Boolean,
)

sealed interface Action {
    data class Tap(
        val target: Int,
    ) : Action

    data class Scroll(
        val target: Int,
        val forward: Boolean,
    ) : Action

    data class Finish(
        val success: Boolean,
        val summary: String,
    ) : Action

    data object TakeOver : Action
}
