package xyz.her3.enco.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import xyz.her3.enco.core.api.Action

internal fun parseAction(reply: String): Action? {
    val raw =
        reply
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    val objectValue =
        try {
            encoJson.parseToJsonElement(raw) as? JsonObject ?: return null
        } catch (_: SerializationException) {
            return null
        }

    fun field(name: String) = objectValue[name] as? JsonPrimitive
    val target = field("target")?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 }
    return when (field("action")?.content) {
        "tap" -> {
            target?.let(Action::Tap)
        }

        "scroll" -> {
            when (field("direction")?.content) {
                "forward" -> target?.let { Action.Scroll(it, true) }
                "backward" -> target?.let { Action.Scroll(it, false) }
                else -> null
            }
        }

        "finish" -> {
            val success = field("success")?.takeUnless { it.isString }?.booleanOrNull ?: return null
            val summary =
                field("summary")
                    ?.takeIf { it.isString }
                    ?.content
                    ?.trim()
                    ?.take(240)
                    .orEmpty()
            if (summary.isBlank()) null else Action.Finish(success, summary)
        }

        "take_over" -> {
            Action.TakeOver
        }

        else -> {
            null
        }
    }
}
