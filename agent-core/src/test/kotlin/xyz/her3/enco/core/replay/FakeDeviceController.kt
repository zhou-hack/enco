package xyz.her3.enco.core.replay

import xyz.her3.enco.core.api.Action
import xyz.her3.enco.core.api.DeviceController
import xyz.her3.enco.core.api.Observation

class FakeDeviceController(
    frames: List<Observation>,
) : DeviceController {
    private val frames = frames.toList()
    private var currentFrameIndex = 0

    val actions = mutableListOf<Action>()
    var failNextAction = false

    override suspend fun observe(): Observation? = frames.getOrNull(currentFrameIndex)

    override suspend fun perform(
        observation: Observation,
        action: Action,
    ): Boolean {
        val currentFrame = observe() ?: return false
        if (observation != currentFrame) return false
        actions += action
        if (failNextAction) {
            failNextAction = false
            return false
        }
        currentFrameIndex++
        return true
    }
}
