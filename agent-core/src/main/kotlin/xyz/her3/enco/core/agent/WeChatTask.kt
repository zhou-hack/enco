package xyz.her3.enco.core.agent

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import xyz.her3.enco.core.protocol.encoJson

data class WeChatDraft(
    val recipient: String,
    val message: String,
)

enum class WeChatTapDecision { EXECUTE, CONFIRM, TAKE_OVER }

enum class WeChatTapReview { NAVIGATION, UNCERTAIN, TAKE_OVER }

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
    val tapGuardPrompt: String = prompt("wechat-tap-guard")

    private val takeover =
        Regex(
            "密码|密碼|验证码|驗證碼|付款|支付|转账|轉帳|红包|紅包|发送|發送|傳送|删除|刪除|提交|授权|授權|password|passcode|verification code|payment|transfer money|\\bsend\\b|\\bdelete\\b|\\bsubmit\\b",
            RegexOption.IGNORE_CASE,
        )

    fun needsManualAction(label: String): Boolean = takeover.containsMatchIn(label)

    private val navigationLabels =
        setOf(
            "微信",
            "通讯录",
            "通訊錄",
            "发现",
            "發現",
            "我",
            "搜索",
            "搜尋",
            "返回",
            "wechat",
            "chats",
            "contacts",
            "discover",
            "me",
            "search",
            "back",
        )

    suspend fun tapDecision(
        permission: PhoneTaskPermission,
        action: VisionAction.Tap,
        review: suspend () -> WeChatTapReview,
    ): WeChatTapDecision {
        if (needsManualAction(action.label)) return WeChatTapDecision.TAKE_OVER
        val checked = review()
        return when {
            checked == WeChatTapReview.TAKE_OVER -> WeChatTapDecision.TAKE_OVER
            checked == WeChatTapReview.NAVIGATION && action.label.trim().lowercase() in navigationLabels -> WeChatTapDecision.EXECUTE
            permission == PhoneTaskPermission.IMPORTANT_ONLY -> WeChatTapDecision.CONFIRM
            else -> WeChatTapDecision.TAKE_OVER
        }
    }

    fun parseTapReview(reply: String): WeChatTapReview =
        when (parse(reply)?.text("decision", 20)) {
            "navigation" -> WeChatTapReview.NAVIGATION
            "uncertain" -> WeChatTapReview.UNCERTAIN
            else -> WeChatTapReview.TAKE_OVER
        }

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
