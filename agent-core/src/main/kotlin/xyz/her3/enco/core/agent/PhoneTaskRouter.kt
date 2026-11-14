package xyz.her3.enco.core.agent

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.her3.enco.core.protocol.encoJson

enum class PhoneTaskKind { SETTINGS, WECHAT_NAVIGATION, WECHAT_MESSAGE, GMAIL_INBOX }

enum class PhoneTaskPermission { FULL, IMPORTANT_ONLY }

data class PhoneTaskRequest(
    val kind: PhoneTaskKind,
    val goal: String,
) : java.io.Serializable

object PhoneTaskRouter {
    const val MAX_GOAL_LENGTH = 500
    val prompt: String =
        checkNotNull(PhoneTaskRouter::class.java.getResource("/prompts/phone-task-router.md")).readText()

    fun parse(reply: String): PhoneTaskRequest? {
        val raw =
            reply
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        val decision =
            try {
                encoJson.parseToJsonElement(raw) as? JsonObject ?: return null
            } catch (_: SerializationException) {
                return null
            }
        val kind =
            when ((decision["route"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                "settings" -> PhoneTaskKind.SETTINGS
                "wechat_navigation" -> PhoneTaskKind.WECHAT_NAVIGATION
                "wechat_message" -> PhoneTaskKind.WECHAT_MESSAGE
                "gmail_inbox" -> PhoneTaskKind.GMAIL_INBOX
                else -> return null
            }
        val goal = (decision["goal"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
        if (goal.length !in 1..MAX_GOAL_LENGTH || goal.any { it.isISOControl() && it != '\n' }) return null
        return PhoneTaskRequest(kind, goal)
    }
}
