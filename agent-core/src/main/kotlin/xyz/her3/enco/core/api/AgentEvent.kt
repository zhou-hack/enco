package xyz.her3.enco.core.api

sealed interface AgentEvent {
    data class TaskStarted(
        val goal: String,
    ) : AgentEvent

    data class StageOpened(
        val stage: Stage,
    ) : AgentEvent

    data class StepStarted(
        val step: Int,
    ) : AgentEvent

    data class ActionSelected(
        val step: Int,
        val action: Action,
        val thought: String? = null,
    ) : AgentEvent

    data class ActionFinished(
        val step: Int,
        val action: Action,
        val result: ActionResult,
    ) : AgentEvent

    data class ConfirmationRequested(
        val action: Action,
        val reason: String,
    ) : AgentEvent

    data class TakeOverRequested(
        val reason: String,
    ) : AgentEvent

    data class ModelUsage(
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val durationMillis: Long,
    ) : AgentEvent

    data class TaskFinished(
        val success: Boolean,
        val summary: String,
    ) : AgentEvent

    data class TaskFailed(
        val message: String,
    ) : AgentEvent
}
