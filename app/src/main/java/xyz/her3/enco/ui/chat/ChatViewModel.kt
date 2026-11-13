package xyz.her3.enco.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.her3.enco.R
import xyz.her3.enco.llm.ChatClient
import xyz.her3.enco.llm.ChatException
import xyz.her3.enco.llm.ChatFailure
import xyz.her3.enco.llm.ChatMessage
import xyz.her3.enco.llm.ModelConfig
import xyz.her3.enco.llm.ModelSettingsStore
import xyz.her3.enco.llm.chatEndpoint

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
        if (current.generating || text.isEmpty()) return

        val prompt = ChatMessage("user", text)
        val history = current.messages + prompt
        mutableState.value = current.copy(draft = "", pending = text, reply = "", generating = true, chatNotice = null)

        responseJob =
            viewModelScope.launch {
                try {
                    client.stream(model, history).collect { chunk ->
                        mutableState.update { it.copy(reply = it.reply + chunk) }
                    }
                    val reply = state.value.reply
                    mutableState.update {
                        it.copy(messages = history + ChatMessage("assistant", reply), pending = "", reply = "")
                    }
                } catch (error: CancellationException) {
                    recoverDraft(text, R.string.chat_stopped)
                    throw error
                } catch (error: Exception) {
                    recoverDraft(text, error.messageResource(R.string.chat_error))
                } finally {
                    mutableState.update { it.copy(generating = false) }
                }
            }
    }

    fun stop() {
        responseJob?.cancel()
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
                            .stream(
                                next,
                                listOf(ChatMessage("user", getApplication<Application>().getString(R.string.model_test_prompt))),
                            ).first()
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
            it.copy(draft = it.draft.ifBlank { text }, chatNotice = notice)
        }
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
