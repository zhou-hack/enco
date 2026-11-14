package xyz.her3.enco.core.agent

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.her3.enco.core.api.Action
import xyz.her3.enco.core.protocol.encoJson
import xyz.her3.enco.core.protocol.parseAction

@Serializable
data class GmailScreen(
    val id: Long,
    val controls: List<GmailControl>,
    val screenText: List<String>,
    val hasMessageBody: Boolean = false,
    val hasMailResults: Boolean = false,
)

@Serializable
data class GmailControl(
    val id: Int,
    val label: String,
    val action: GmailAction,
)

@Serializable
enum class GmailAction { TAP, OPEN_MESSAGE, SEARCH, SCROLL_FORWARD, SCROLL_BACKWARD }

class GmailOperator {
    suspend fun run(
        goal: String,
        observe: suspend () -> String?,
        execute: suspend (Long, Int, String?) -> Boolean,
        decide: suspend (String, String) -> String,
        onStep: (Int) -> Unit = {},
        settle: suspend () -> Unit = { delay(700) },
    ): TaskResult {
        val history = mutableListOf<JsonObject>()
        var observedMail = false
        var searched = false
        var opened = false
        var bodyRead = false
        val needsSearch = searchGoal.containsMatchIn(goal)
        val needsBody = bodyGoal.containsMatchIn(goal)
        var unavailable = 0
        var unchanged = 0
        var previous: GmailScreen? = null
        var feedback = ""
        repeat(20) { step ->
            onStep(step + 1)
            val screen = observe()?.let { encoJson.decodeFromString<GmailScreen>(it) }
            if (screen == null || (screen.controls.isEmpty() && screen.screenText.isEmpty())) {
                if (++unavailable >= 3) return TaskResult.ScreenUnavailable
                settle()
                return@repeat
            }
            unavailable = 0
            observedMail = observedMail || screen.hasMessageBody || screen.hasMailResults
            if (opened && screen.hasMessageBody) bodyRead = true
            unchanged =
                if (previous?.let { it.controls == screen.controls && it.screenText == screen.screenText } == true) unchanged + 1 else 0
            if (unchanged >= 4) return TaskResult.Stalled
            previous = screen
            val input =
                buildJsonObject {
                    put("goal", goal)
                    put("previous_steps", JsonArray(history.takeLast(8)))
                    put("screen_data", encoJson.encodeToJsonElement(GmailScreen.serializer(), screen))
                    put("feedback", feedback)
                }
            val action = decideAction(input.toString(), decide) ?: return TaskResult.InvalidReply
            when (action) {
                is Action.Finish -> {
                    val enough =
                        observedMail &&
                            (!needsSearch || searched) &&
                            (!needsBody || bodyRead)
                    if (!action.success || enough) return TaskResult.Finished(action.success, action.summary)
                    feedback = "还未实际完成相关搜索或读到正文，请继续查看 Gmail。"
                    return@repeat
                }

                Action.TakeOver -> {
                    return TaskResult.TakeOver
                }

                is Action.Wait -> {
                    settle()
                    return@repeat
                }

                else -> {
                }
            }
            val target =
                when (action) {
                    is Action.Tap -> action.target
                    is Action.Type -> action.target
                    else -> return TaskResult.InvalidReply
                }
            val control = screen.controls.firstOrNull { it.id == target }
            val search = (action as? Action.Type)?.text
            val valid =
                control != null && (search != null) == (control.action == GmailAction.SEARCH) &&
                    (search == null || (search.length <= 160 && search.none(Char::isISOControl)))
            val applied = valid && execute(screen.id, target, search)
            if (applied && search != null) {
                searched = true
                opened = false
                bodyRead = false
            }
            if (applied && control?.action == GmailAction.OPEN_MESSAGE) {
                opened = true
                bodyRead = false
            }
            history +=
                buildJsonObject {
                    put("control", control?.label)
                    put("query", search)
                    put("executed", applied)
                    put("screen_text", JsonArray(screen.screenText.take(20).map(::JsonPrimitive)))
                }
            feedback = if (applied) "上一步已执行，观察新页面核验。" else "动作未执行，请从新页面给出的控件重新选择。"
            settle()
        }
        return TaskResult.StepLimit
    }

    private suspend fun decideAction(
        input: String,
        decide: suspend (String, String) -> String,
    ): Action? {
        repeat(3) { attempt ->
            val reply = decide(prompt, if (attempt == 0) input else "$input\n上一回复无效，请只输出一个有效动作。")
            try {
                parseAction(reply)?.let { return it }
            } catch (_: SerializationException) {
                // 允许模型修正本步输出。
            }
        }
        return null
    }

    private companion object {
        val prompt = checkNotNull(GmailOperator::class.java.getResource("/prompts/gmail-operator.md")).readText()
        val searchGoal = Regex("查找|找一下|找到|搜[索尋]|发来|發來|寄來|寄来|from\\s*:|sent by|search|find", RegexOption.IGNORE_CASE)
        val bodyGoal = Regex("总结|總結|概括|摘要|正文|内[容文]|內[容文]|summari[sz]e|summary|body", RegexOption.IGNORE_CASE)
    }
}
