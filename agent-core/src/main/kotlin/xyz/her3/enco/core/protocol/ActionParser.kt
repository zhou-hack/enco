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

    fun intField(name: String): Int? = field(name)?.takeUnless { it.isString }?.intOrNull

    fun targetField(): Int? = intField("target")?.takeIf { it > 0 }

    fun textField(
        name: String,
        maxLength: Int,
        trim: Boolean = true,
        allowNewline: Boolean = false,
    ): String? {
        val value = field(name)?.takeIf { it.isString }?.content ?: return null
        if (value.length > maxLength || value.any { it.isISOControl() && !(allowNewline && it == '\n') }) return null
        return (if (trim) value.trim() else value).takeIf { it.isNotEmpty() }
    }

    fun coordinates(): Pair<Int, Int>? {
        val x = intField("x")?.takeIf { it in 0..1000 } ?: return null
        val y = intField("y")?.takeIf { it in 0..1000 } ?: return null
        return x to y
    }

    fun targetedOrCoordinates(
        targeted: (Int) -> Action,
        at: (Int, Int) -> Action,
    ): Action? {
        val hasTarget = "target" in objectValue
        val hasCoordinates = "x" in objectValue || "y" in objectValue
        return when {
            hasTarget && !hasCoordinates -> {
                targetField()?.let(targeted)
            }

            !hasTarget && "x" in objectValue && "y" in objectValue -> {
                coordinates()?.let { (x, y) -> at(x, y) }
            }

            else -> {
                null
            }
        }
    }

    return when (field("action")?.takeIf { it.isString }?.content) {
        "tap" -> {
            targetedOrCoordinates(Action::Tap, Action::TapAt)
        }

        "long_press" -> {
            targetedOrCoordinates(Action::LongPress, Action::LongPressAt)
        }

        "type" -> {
            val target = targetField() ?: return null
            val text = textField("text", 500, trim = false, allowNewline = true) ?: return null
            val submit =
                if ("submit" in objectValue) {
                    field("submit")?.takeUnless { it.isString }?.booleanOrNull ?: return null
                } else {
                    false
                }
            Action.Type(target, text, submit)
        }

        "scroll" -> {
            when (field("direction")?.takeIf { it.isString }?.content) {
                "forward" -> targetField()?.let { Action.Scroll(it, true) }
                "backward" -> targetField()?.let { Action.Scroll(it, false) }
                else -> null
            }
        }

        "swipe" -> {
            val direction =
                textField("direction", 16)?.takeIf { it in setOf("up", "down", "left", "right") }
                    ?: return null
            val distance =
                if ("distance" in objectValue) {
                    intField("distance")?.takeIf { it in 1..1000 } ?: return null
                } else {
                    500
                }
            val target =
                if ("target" in objectValue) {
                    targetField() ?: return null
                } else {
                    null
                }
            Action.Swipe(direction, distance, target)
        }

        "back" -> {
            Action.Back
        }

        "home" -> {
            Action.Home
        }

        "launch" -> {
            textField("app", 200)?.let(Action::Launch)
        }

        "wait" -> {
            intField("ms")?.takeIf { it in 1..60_000 }?.let(Action::Wait)
        }

        "ask_user" -> {
            textField("question", 500)?.let(Action::AskUser)
        }

        "remember" -> {
            textField("fact", 500)?.let(Action::Remember)
        }

        "take_over" -> {
            if ("reason" in objectValue && textField("reason", 240) == null) return null
            Action.TakeOver
        }

        "take_over_request" -> {
            textField("reason", 240)?.let(Action::TakeOverRequest)
        }

        "finish" -> {
            val success = field("success")?.takeUnless { it.isString }?.booleanOrNull ?: return null
            val summary = textField("summary", 240) ?: return null
            Action.Finish(success, summary)
        }

        else -> {
            null
        }
    }
}
