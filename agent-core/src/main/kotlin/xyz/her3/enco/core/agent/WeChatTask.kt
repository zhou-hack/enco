package xyz.her3.enco.core.agent

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import xyz.her3.enco.core.protocol.encoJson

enum class PhoneTaskKind { SETTINGS, WECHAT_NAVIGATION, WECHAT_MESSAGE }

private val weChatName = Regex("微信|wechat", RegexOption.IGNORE_CASE)
private val messageRequest = Regex("发送|發送|发个|發個|发条|發條|发一条|發一條|发消息|發消息|发信息|發信息|发讯息|發訊息|传送|傳送|\\bsend\\b", RegexOption.IGNORE_CASE)
private val phoneRequest =
    Regex(
        "^(?:(?:请|請|帮我|幫我|替我|你能|能不能|让你|讓你)\\s*)*(?:打开|打開|进入|進入|去|用|在|[给給](?!我解释|我解釋|我介绍|我介紹)|发|發|查看)|^(?:please\\s+)?(?:open|go to|send|launch)\\b",
        RegexOption.IGNORE_CASE,
    )
private val settingsName = Regex("设置|設定|蓝牙|藍牙|亮度|\\bsettings\\b|\\bbluetooth\\b", RegexOption.IGNORE_CASE)

fun isPhoneTaskRequest(goal: String): Boolean =
    phoneRequest.containsMatchIn(goal.trim()) && (weChatName.containsMatchIn(goal) || settingsName.containsMatchIn(goal))

fun phoneTaskKind(goal: String): PhoneTaskKind {
    if (!weChatName.containsMatchIn(goal)) return PhoneTaskKind.SETTINGS
    return if (messageRequest.containsMatchIn(goal) || Regex("[给給].{1,50}[发發]").containsMatchIn(goal)) {
        PhoneTaskKind.WECHAT_MESSAGE
    } else {
        PhoneTaskKind.WECHAT_NAVIGATION
    }
}

data class WeChatDraft(
    val recipient: String,
    val message: String,
)

sealed interface VisionAction {
    data class Tap(
        val x: Int,
        val y: Int,
        val label: String,
    ) : VisionAction

    data class Scroll(
        val up: Boolean,
    ) : VisionAction

    data class Finish(
        val success: Boolean,
        val summary: String,
    ) : VisionAction

    data object TakeOver : VisionAction
}

object WeChatTask {
    val draftPrompt: String = prompt("wechat-draft")
    val visionPrompt: String = prompt("wechat-vision")

    private val takeover =
        Regex(
            "密码|密碼|验证码|驗證碼|付款|支付|转账|轉帳|红包|紅包|发送|發送|傳送|删除|刪除|提交|授权|授權|password|verification code|payment|transfer money|\\bsend\\b|\\bdelete\\b|\\bsubmit\\b",
            RegexOption.IGNORE_CASE,
        )

    fun needsManualAction(label: String): Boolean = takeover.containsMatchIn(label)

    fun parseDraft(reply: String): WeChatDraft? {
        val fields = parse(reply) ?: return null
        val recipient = fields.text("recipient", 80) ?: return null
        val message = fields.text("message", 500) ?: return null
        if (recipient.any(Char::isISOControl) || message.any { it.isISOControl() && it != '\n' }) return null
        return WeChatDraft(recipient, message)
    }

    fun parseVision(reply: String): VisionAction? {
        val fields = parse(reply) ?: return null
        return when (fields.text("action", 20)) {
            "tap" -> {
                val x = fields.coordinate("x") ?: return null
                val y = fields.coordinate("y") ?: return null
                val label = fields.text("label", 80) ?: return null
                VisionAction.Tap(x, y, label)
            }

            "scroll" -> {
                when (fields.text("direction", 12)) {
                    "up" -> VisionAction.Scroll(up = true)
                    "down" -> VisionAction.Scroll(up = false)
                    else -> null
                }
            }

            "finish" -> {
                val success = (fields["success"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
                val summary = fields.text("summary", 240) ?: return null
                VisionAction.Finish(success, summary)
            }

            "take_over" -> {
                VisionAction.TakeOver
            }

            else -> {
                null
            }
        }
    }

    private fun parse(reply: String): JsonObject? {
        val raw =
            reply
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        return try {
            encoJson.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
    }

    private fun JsonObject.text(
        name: String,
        maxLength: Int,
    ): String? =
        (get(name) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.length in 1..maxLength }

    private fun JsonObject.coordinate(name: String): Int? =
        (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0..1000 }

    private fun prompt(name: String): String = checkNotNull(WeChatTask::class.java.getResource("/prompts/$name.md")).readText()
}
