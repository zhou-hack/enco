package xyz.her3.enco.core.agent

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.her3.enco.core.protocol.encoJson

enum class PhoneTaskKind(
    internal val toolValue: String,
) {
    SETTINGS("settings"),
    WECHAT_NAVIGATION("wechat_navigation"),
    WECHAT_MESSAGE("wechat_message"),
    GMAIL_INBOX("gmail_inbox"),
}

enum class PhoneTaskPermission { FULL, IMPORTANT_ONLY }

data class PhoneTaskRequest(
    val kind: PhoneTaskKind,
    val goal: String,
) : java.io.Serializable

object PhoneTaskTool {
    const val NAME = "phone_control"
    const val MAX_GOAL_LENGTH = 500
    val prompt: String =
        checkNotNull(PhoneTaskTool::class.java.getResource("/prompts/chat.md")).readText()

    val parameters: JsonObject =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "kind",
                        buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { PhoneTaskKind.entries.forEach { add(JsonPrimitive(it.toolValue)) } })
                        },
                    )
                    put("goal", buildJsonObject { put("type", "string") })
                },
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("kind"))
                    add(JsonPrimitive("goal"))
                },
            )
            put("additionalProperties", false)
        }

    fun parse(
        name: String,
        arguments: String,
    ): PhoneTaskRequest? {
        if (name != NAME) return null
        val input =
            try {
                encoJson.parseToJsonElement(arguments) as? JsonObject ?: return null
            } catch (_: SerializationException) {
                return null
            }
        if (input.keys != setOf("kind", "goal")) return null
        val value = (input["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val kind = PhoneTaskKind.entries.firstOrNull { it.toolValue == value } ?: return null
        val goal = (input["goal"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
        if (!validGoal(goal)) return null
        return PhoneTaskRequest(kind, goal)
    }

    fun validGoal(goal: String): Boolean =
        goal.isNotBlank() && goal.length <= MAX_GOAL_LENGTH && goal.none { it.isISOControl() && it != '\n' }
}
