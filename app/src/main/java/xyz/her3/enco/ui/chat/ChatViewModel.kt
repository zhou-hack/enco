package xyz.her3.enco.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.her3.enco.R
import xyz.her3.enco.core.agent.PhoneTaskKind
import xyz.her3.enco.core.agent.PhoneTaskRequest
import xyz.her3.enco.core.agent.PhoneTaskTool
import xyz.her3.enco.core.protocol.encoJson
import xyz.her3.enco.device.service.PhoneTaskService
import xyz.her3.enco.device.service.PhoneTaskStatus
import xyz.her3.enco.llm.ChatClient
import xyz.her3.enco.llm.ChatException
import xyz.her3.enco.llm.ChatFailure
import xyz.her3.enco.llm.ChatMessage
import xyz.her3.enco.llm.ChatTool
import xyz.her3.enco.llm.ModelConfig
import xyz.her3.enco.llm.ModelSettingsStore
import xyz.her3.enco.llm.chatEndpoint
import xyz.her3.enco.ui.MainActivity
import java.util.UUID

internal data class ChatState(
    val model: String = "",
    val endpoint: String = "",
    val hasSavedKey: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val reply: String = "",
    val pending: String = "",
    val loading: Boolean = true,
    val generating: Boolean = false,
    val phoneTurn: Boolean = false,
    val phoneRequest: PhoneTaskRequest? = null,
    val chatNotice: Int? = null,
    val settingsBusy: Boolean = false,
    val settingsNotice: Int? = null,
)

internal class ChatViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val settings = ModelSettingsStore(application)
    private val client = ChatClient()
    private var config: ModelConfig? = null
    private var responseJob: Job? = null
    private var settingsJob: Job? = null
    private var conversation = emptyList<ChatMessage>()
    private var phoneApproval: CompletableDeferred<PhoneApproval>? = null
    private val mutableState = MutableStateFlow(ChatState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                settings.load()?.let(::setConfig)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(settingsNotice = R.string.model_load_error) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun editDraft(text: String) {
        mutableState.update { it.copy(draft = text) }
    }

    fun send() {
        val model = config ?: return
        val current = state.value
        val text = current.draft.trim()
        if (current.generating || current.phoneTurn || text.isEmpty()) return

        val prompt = ChatMessage("user", text)
        val history = listOf(ChatMessage("system", PhoneTaskTool.prompt)) + conversation + prompt
        mutableState.value = current.copy(draft = "", pending = text, reply = "", generating = true, chatNotice = null)

        responseJob =
            viewModelScope.launch {
                try {
                    val response = client.chat(model, history, tool = ChatTool.PHONE_CONTROL, onText = ::appendReply)
                    val call = response.toolCalls?.single()
                    if (call != null) {
                        val request =
                            PhoneTaskTool.parse(call.function.name, call.function.arguments)
                                ?: throw ChatException(ChatFailure.INVALID_RESPONSE)
                        runPhoneTool(model, history + response, prompt, request, call.id)
                        return@launch
                    }
                    conversation += listOf(prompt, response)
                    finishTurn(prompt, checkNotNull(response.content))
                } catch (error: CancellationException) {
                    if (state.value.pending.isNotEmpty()) recoverDraft(text, R.string.chat_stopped)
                    throw error
                } catch (error: Exception) {
                    Log.w("enco.chat", "聊天请求中断：${error.javaClass.simpleName}")
                    recoverDraft(text, error.messageResource(R.string.chat_error))
                } finally {
                    phoneApproval = null
                    mutableState.update { it.copy(generating = false, phoneTurn = false, phoneRequest = null) }
                }
            }
    }

    fun stop() {
        responseJob?.cancel()
    }

    fun approvePhoneRequest(
        request: PhoneTaskRequest,
        start: (String) -> Boolean,
    ) {
        val approval = phoneApproval ?: return
        if (approval.isCompleted || state.value.phoneRequest != request) return
        val taskId = UUID.randomUUID().toString()
        val outcome =
            when {
                PhoneTaskService.status.value.running -> PhoneApproval.Rejected(R.string.phone_task_busy)
                start(taskId) -> PhoneApproval.Started(taskId)
                else -> PhoneApproval.Rejected(R.string.phone_task_error)
            }
        approval.complete(outcome)
        mutableState.update { it.copy(phoneRequest = null) }
    }

    fun declinePhoneRequest(notice: Int = R.string.phone_task_permission_cancelled) {
        phoneApproval?.complete(PhoneApproval.Rejected(notice))
        mutableState.update { it.copy(phoneRequest = null) }
    }

    private suspend fun runPhoneTool(
        model: ModelConfig,
        history: List<ChatMessage>,
        prompt: ChatMessage,
        request: PhoneTaskRequest,
        callId: String,
    ) {
        val approval = CompletableDeferred<PhoneApproval>()
        phoneApproval = approval
        mutableState.update { it.copy(generating = false, phoneTurn = true, reply = "", phoneRequest = request) }
        val status =
            when (val decision = approval.await()) {
                is PhoneApproval.Started -> {
                    withTimeoutOrNull(610_000) {
                        PhoneTaskService.status.first { it.taskId == decision.taskId && !it.running }
                    } ?: PhoneTaskStatus(R.string.phone_task_result_timeout)
                }

                is PhoneApproval.Rejected -> {
                    PhoneTaskStatus(decision.notice)
                }
            }
        val statusText = getApplication<Application>().getString(status.message)
        val localDetail = status.detail.takeIf { request.kind == PhoneTaskKind.GMAIL_INBOX }.orEmpty()
        val canOpenResult =
            status.taskId != null && status.detail.isNotBlank() &&
                (status.completed || localDetail.isNotBlank()) && request.kind != PhoneTaskKind.WECHAT_MESSAGE
        val fallback = localResult(statusText, status.detail)
        val result =
            buildJsonObject {
                put("status", statusText)
                if (localDetail.isNotBlank()) {
                    put("details_displayed_on_device", true)
                } else if (status.detail.isNotBlank()) {
                    put("detail", status.detail)
                }
                if (canOpenResult) put("result_available_in_chat", true)
            }.toString()
        val toolMessage = ChatMessage("tool", result, toolCallId = callId)
        val completedHistory = history + toolMessage
        conversation = completedHistory.drop(1)
        mutableState.update { it.copy(generating = true) }
        try {
            val answer =
                client.chat(
                    model,
                    completedHistory,
                    tool = ChatTool.OPEN_TASK_RESULT.takeIf { canOpenResult },
                    onText = ::appendReply,
                )
            val openResult = answer.toolCalls?.single()
            if (openResult != null && openResult.function.arguments.isNotBlank()) {
                val arguments =
                    try {
                        encoJson.parseToJsonElement(openResult.function.arguments)
                    } catch (_: SerializationException) {
                        throw ChatException(ChatFailure.INVALID_RESPONSE)
                    }
                if (arguments != JsonNull && (arguments !is JsonObject || arguments.isNotEmpty())) {
                    throw ChatException(ChatFailure.INVALID_RESPONSE)
                }
            }
            conversation += answer
            if (openResult != null) {
                finishTurn(prompt, answer.content?.let { localResult(it, localDetail) } ?: fallback)
                val taskId = checkNotNull(status.taskId)
                PhoneTaskService.offerResult(taskId)
                MainActivity.showTaskResult(getApplication(), taskId)
                conversation += ChatMessage("tool", """{"status":"result_available_in_enco"}""", toolCallId = openResult.id)
            } else {
                finishTurn(prompt, localResult(checkNotNull(answer.content), localDetail))
            }
        } catch (error: CancellationException) {
            finishTurn(prompt, fallback)
            throw error
        } catch (error: ChatException) {
            mutableState.update { it.copy(chatNotice = error.messageResource(R.string.chat_error)) }
            finishTurn(prompt, fallback)
        } catch (error: Exception) {
            Log.w("enco.chat", "手机结果回复中断：${error.javaClass.simpleName}，${error.stackTrace.firstOrNull()}")
            finishTurn(prompt, fallback)
        }
    }

    private fun localResult(
        summary: String,
        detail: String,
    ): String = if (detail.isBlank()) summary else "$summary\n\n$detail"

    private fun appendReply(chunk: String) {
        mutableState.update { it.copy(reply = it.reply + chunk) }
    }

    private fun finishTurn(
        prompt: ChatMessage,
        reply: String,
    ) {
        mutableState.update {
            it.copy(messages = it.messages + listOf(prompt, ChatMessage("assistant", reply)), pending = "", reply = "")
        }
    }

    fun clearSettingsNotice() {
        mutableState.update { it.copy(settingsNotice = null) }
    }

    fun cancelSettingsAction() {
        settingsJob?.cancel()
    }

    fun submitSettings(
        endpoint: String,
        model: String,
        key: String,
        testOnly: Boolean,
        onSaved: () -> Unit,
    ) {
        if (state.value.loading || state.value.settingsBusy) return
        val url = chatEndpoint(endpoint)
        if (url == null || model.isBlank() || key.trim().any { it !in '!'..'~' }) {
            mutableState.update { it.copy(settingsNotice = R.string.model_invalid) }
            return
        }
        val saved = config
        val secret = key.trim().ifEmpty { saved?.takeIf { it.endpoint == url }?.apiKey.orEmpty() }
        val next = ModelConfig(url, model.trim(), secret)
        mutableState.update { it.copy(settingsBusy = true, settingsNotice = null) }
        settingsJob =
            viewModelScope.launch {
                try {
                    if (testOnly) {
                        client
                            .chat(
                                next,
                                listOf(
                                    ChatMessage("system", PhoneTaskTool.prompt),
                                    ChatMessage("user", getApplication<Application>().getString(R.string.model_test_prompt)),
                                ),
                                tool = ChatTool.PHONE_CONTROL,
                                onText = {},
                            ).also { if (it.toolCalls != null) throw ChatException(ChatFailure.INVALID_RESPONSE) }
                        mutableState.update { it.copy(settingsNotice = R.string.model_test_success) }
                    } else {
                        settings.save(next)
                        setConfig(next)
                        onSaved()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutableState.update { it.copy(settingsNotice = error.messageResource(R.string.model_save_error)) }
                } finally {
                    mutableState.update { it.copy(settingsBusy = false) }
                }
            }
    }

    private fun setConfig(next: ModelConfig) {
        val endpointChanged = config?.let { it.endpoint != next.endpoint } == true
        if (endpointChanged) conversation = emptyList()
        config = next
        mutableState.update {
            val chat = if (endpointChanged) it.copy(messages = emptyList(), pending = "", reply = "", chatNotice = null) else it
            chat.copy(
                model = next.model,
                endpoint = next.endpoint.toString(),
                hasSavedKey = next.apiKey.isNotEmpty(),
                settingsNotice = null,
            )
        }
    }

    private fun recoverDraft(
        text: String,
        notice: Int,
    ) {
        mutableState.update {
            it.copy(draft = it.draft.ifBlank { text }, pending = "", reply = "", chatNotice = notice)
        }
    }

    private sealed interface PhoneApproval {
        data class Started(
            val taskId: String,
        ) : PhoneApproval

        data class Rejected(
            val notice: Int,
        ) : PhoneApproval
    }
}

private fun Exception.messageResource(fallback: Int): Int =
    when ((this as? ChatException)?.reason) {
        ChatFailure.UNAUTHORIZED -> R.string.model_unauthorized
        ChatFailure.RATE_LIMITED -> R.string.model_rate_limit
        ChatFailure.ENDPOINT -> R.string.model_endpoint_error
        ChatFailure.HTTP -> R.string.model_http_error
        ChatFailure.NETWORK -> R.string.model_network_error
        ChatFailure.TIMEOUT -> R.string.model_timeout
        ChatFailure.INVALID_RESPONSE, ChatFailure.EMPTY_RESPONSE -> R.string.model_invalid_response
        null -> fallback
    }
