package xyz.her3.enco.core.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.her3.enco.core.api.Action
import xyz.her3.enco.core.api.DeviceController
import xyz.her3.enco.core.api.Observation
import xyz.her3.enco.core.guard.SettingsGuard
import xyz.her3.enco.core.protocol.parseAction

sealed interface TaskResult {
    data class Finished(
        val success: Boolean,
        val summary: String,
    ) : TaskResult

    data object TakeOver : TaskResult

    data object ScreenUnavailable : TaskResult

    data object InvalidReply : TaskResult

    data object Stalled : TaskResult

    data object StepLimit : TaskResult
}

class PhoneOperator(
    private val device: DeviceController,
) {
    suspend fun run(
        goal: String,
        decide: suspend (system: String, input: String) -> String,
        onStep: (Int) -> Unit = {},
        afterAction: suspend () -> Unit = {},
    ): TaskResult {
        var previous: Observation? = null
        var unchanged = 0
        var feedback = ""
        repeat(12) { step ->
            currentCoroutineContext().ensureActive()
            val screen = device.observe() ?: return TaskResult.ScreenUnavailable
            if (screen.requiresTakeover) return TaskResult.TakeOver
            if (screen.elements.isEmpty()) return TaskResult.ScreenUnavailable
            unchanged = if (previous?.elements == screen.elements) unchanged + 1 else 0
            if (unchanged >= 3) return TaskResult.Stalled
            onStep(step + 1)
            val action = choose(screen, goal, feedback, decide) ?: return TaskResult.InvalidReply
            when (action) {
                is Action.Finish -> {
                    return TaskResult.Finished(action.success, action.summary)
                }

                Action.TakeOver -> {
                    return TaskResult.TakeOver
                }

                else -> {
                    if (!allows(screen, action)) return TaskResult.TakeOver
                    currentCoroutineContext().ensureActive()
                    val targetLabel =
                        (action as? Action.Tap)?.let { tap ->
                            screen.elements.first { it.id == tap.target }.label
                        }
                    feedback =
                        if (device.perform(screen, action)) {
                            if (targetLabel == null) {
                                "上一步已滚动。根据新界面检查结果。"
                            } else {
                                "上一步已点击「$targetLabel」。根据新界面检查结果。"
                            }
                        } else {
                            "上一步未执行，目标可能已变化。请根据当前界面选择。"
                        }
                    afterAction()
                }
            }
            previous = screen
        }
        return TaskResult.StepLimit
    }

    private suspend fun choose(
        screen: Observation,
        goal: String,
        feedback: String,
        decide: suspend (String, String) -> String,
    ): Action? {
        var note = feedback
        repeat(3) {
            val input =
                buildJsonObject {
                    put("goal", goal)
                    put("previous_step", note)
                    put(
                        "screen_data",
                        JsonArray(
                            screen.elements.map { element ->
                                buildJsonObject {
                                    put("id", element.id)
                                    put("label", JsonPrimitive(element.label))
                                    put("tap", element.canTap && SettingsGuard.canTap(element.label))
                                    put("scroll_forward", element.canScrollForward)
                                    put("scroll_backward", element.canScrollBackward)
                                }
                            },
                        ),
                    )
                }
            parseAction(decide(prompt, input.toString()))?.let { return it }
            note = "上次回复不符合动作协议。请只返回一个合法的 JSON 对象。"
        }
        return null
    }

    private fun allows(
        screen: Observation,
        action: Action,
    ): Boolean =
        when (action) {
            is Action.Tap -> {
                screen.elements.any {
                    it.id == action.target && it.canTap && SettingsGuard.canTap(it.label)
                }
            }

            is Action.Scroll -> {
                screen.elements.any {
                    it.id == action.target && if (action.forward) it.canScrollForward else it.canScrollBackward
                }
            }

            else -> {
                false
            }
        }

    private companion object {
        val prompt =
            checkNotNull(PhoneOperator::class.java.getResource("/prompts/phone-operator.md"))
                .readText()
    }
}
