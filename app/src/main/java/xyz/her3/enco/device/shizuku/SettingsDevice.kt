package xyz.her3.enco.device.shizuku

import xyz.her3.enco.core.api.Action
import xyz.her3.enco.core.api.DeviceController
import xyz.her3.enco.core.api.Observation
import xyz.her3.enco.core.api.ScreenElement
import xyz.her3.enco.device.shell.IEncoShellService

internal class SettingsDevice(
    private val service: IEncoShellService,
) : DeviceController {
    override suspend fun observe(): Observation? =
        service.observeSettings()?.let { frame ->
            Observation(
                frame.id,
                frame.elements.map {
                    ScreenElement(it.id, it.label, it.canTap, it.canScrollForward, it.canScrollBackward)
                },
                frame.requiresTakeover,
            )
        }

    override suspend fun perform(
        observation: Observation,
        action: Action,
    ): Boolean =
        when (action) {
            is Action.Tap -> service.performSettingsAction(observation.id, action.target, 0)
            is Action.Scroll -> service.performSettingsAction(observation.id, action.target, if (action.forward) 1 else 2)
            else -> false
        }
}
