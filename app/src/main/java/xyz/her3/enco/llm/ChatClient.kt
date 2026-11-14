package xyz.her3.enco.llm

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import xyz.her3.enco.core.agent.PhoneTaskTool
import xyz.her3.enco.core.api.LlmMessage
import xyz.her3.enco.core.api.LlmPart
import xyz.her3.enco.core.protocol.encoJson
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class ModelConfig(
    val endpoint: HttpUrl,
    val model: String,
    val apiKey: String,
)

@Serializable
internal data class ChatMessage(
    val role: String,
    val content: String?,
    @SerialName("tool_calls") val toolCalls: List<ChatToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("reasoning_content") val reasoning: String? = null,
)

@Serializable
internal data class ChatToolCall(
    val id: String,
    val type: String,
    val function: ChatFunctionCall,
)

@Serializable
internal data class ChatFunctionCall(
    val name: String,
    val arguments: String,
)

internal enum class ChatFailure {
    UNAUTHORIZED,
    RATE_LIMITED,
    ENDPOINT,
    HTTP,
    NETWORK,
    TIMEOUT,
    INVALID_RESPONSE,
    EMPTY_RESPONSE,
}

internal class ChatException(
    val reason: ChatFailure,
    val status: Int? = null,
) : IOException(reason.name)

internal fun chatEndpoint(input: String): HttpUrl? {
    val url = input.trim().toHttpUrlOrNull() ?: return null
    if (!url.isHttps && url.host !in setOf("127.0.0.1", "localhost")) return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null

    val path = url.encodedPath.trimEnd('/')
    return url
        .newBuilder()
        .encodedPath(
            if (path.endsWith("/chat/completions")) path else "$path/chat/completions",
        ).build()
}

internal class ChatClient(
    private val firstTokenTimeoutMillis: Long = 60_000,
    private val rateLimitDelayMillis: Long = 1_000,
) {
    private val events =
        EventSources.createFactory(
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )

    fun stream(
        config: ModelConfig,
        messages: List<ChatMessage>,
    ): Flow<String> =
        streamRequest(
            config,
            messages.map {
                RequestMessage(it.role, JsonPrimitive(it.content))
            },
        ).mapNotNull { it.delta?.content?.takeIf(String::isNotEmpty) }

    suspend fun chat(
        config: ModelConfig,
        messages: List<ChatMessage>,
        allowPhoneTool: Boolean,
        onText: (String) -> Unit,
    ): ChatMessage {
        val tools =
            if (allowPhoneTool) {
                listOf(
                    ChatTool(
                        "function",
                        FunctionDefinition(PhoneTaskTool.NAME, "在用户授权后执行本次手机任务", PhoneTaskTool.parameters),
                    ),
                )
            } else {
                null
            }
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val arguments = StringBuilder()
        var call: ToolDelta? = null
        var finishReason: String? = null
        streamRequest(
            config,
            messages.map {
                RequestMessage(it.role, it.content?.let(::JsonPrimitive) ?: JsonNull, it.toolCalls, it.toolCallId, it.reasoning)
            },
            tools,
        ).collect { choice ->
            if (choice.finishReason != null) finishReason = choice.finishReason
            choice.delta?.content?.takeIf(String::isNotEmpty)?.let {
                content.append(it)
                onText(it)
            }
            choice.delta?.reasoning?.let(reasoning::append)
            for (delta in choice.delta?.toolCalls.orEmpty()) {
                if (!allowPhoneTool || delta.index != 0 || (call != null && delta.id != null && delta.id != call?.id)) {
                    throw ChatException(ChatFailure.INVALID_RESPONSE)
                }
                if (call == null) call = delta
                val first = checkNotNull(call)
                if ((delta.type != null && delta.type != first.type) ||
                    (delta.function?.name != null && delta.function.name != first.function?.name)
                ) {
                    throw ChatException(ChatFailure.INVALID_RESPONSE)
                }
                val part = delta.function?.arguments.orEmpty()
                if (arguments.length + part.length > 4096) throw ChatException(ChatFailure.INVALID_RESPONSE)
                arguments.append(part)
            }
        }
        val completedCall =
            call?.let {
                val id = it.id?.takeIf { id -> id.length in 1..256 && id.none(Char::isWhitespace) }
                val name = it.function?.name
                if (finishReason != "tool_calls" || id == null || name.isNullOrBlank() || it.type != "function") {
                    throw ChatException(ChatFailure.INVALID_RESPONSE)
                }
                ChatToolCall(id, it.type, ChatFunctionCall(name, arguments.toString()))
            }
        if (completedCall == null && (finishReason == "tool_calls" || content.isEmpty())) {
            throw ChatException(ChatFailure.INVALID_RESPONSE)
        }
        return ChatMessage(
            "assistant",
            content.toString().takeIf(String::isNotEmpty),
            toolCalls = completedCall?.let(::listOf),
            reasoning = reasoning.toString().takeIf(String::isNotEmpty),
        )
    }

    fun streamLlm(
        config: ModelConfig,
        messages: List<LlmMessage>,
    ): Flow<String> =
        streamRequest(
            config,
            messages.map { message ->
                val content =
                    (message.parts.singleOrNull() as? LlmPart.Text)?.let { JsonPrimitive(it.text) }
                        ?: buildJsonArray {
                            message.parts.forEach { part ->
                                when (part) {
                                    is LlmPart.Text -> {
                                        add(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", part.text)
                                            },
                                        )
                                    }

                                    is LlmPart.Image -> {
                                        add(
                                            buildJsonObject {
                                                put("type", "image_url")
                                                put("image_url", buildJsonObject { put("url", part.url) })
                                            },
                                        )
                                    }
                                }
                            }
                        }
                RequestMessage(message.role.name.lowercase(), content)
            },
        ).mapNotNull { it.delta?.content?.takeIf(String::isNotEmpty) }

    fun streamImage(
        config: ModelConfig,
        system: String,
        prompt: String,
        imageUrl: String,
    ): Flow<String> =
        streamRequest(
            config,
            listOf(
                RequestMessage("system", JsonPrimitive(system)),
                RequestMessage(
                    "user",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", prompt)
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject { put("url", imageUrl) })
                            },
                        )
                    },
                ),
            ),
        ).mapNotNull { it.delta?.content?.takeIf(String::isNotEmpty) }

    private fun streamRequest(
        config: ModelConfig,
        messages: List<RequestMessage>,
        tools: List<ChatTool>? = null,
    ): Flow<Choice> {
        val payload = encoJson.encodeToString(ChatRequest(config.model, messages, stream = true, tools = tools))
        val request =
            Request
                .Builder()
                .url(config.endpoint)
                .header("Accept", "text/event-stream")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .apply { if (config.apiKey.isNotEmpty()) header("Authorization", "Bearer ${config.apiKey}") }
                .build()

        return readEvents(request, tools != null || messages.any { it.toolCallId != null }).retryWhen { failure, attempt ->
            if (attempt == 0L && (failure as? ChatException)?.reason == ChatFailure.RATE_LIMITED) {
                delay(rateLimitDelayMillis)
                true
            } else {
                false
            }
        }
    }

    private fun readEvents(
        request: Request,
        conversation: Boolean,
    ): Flow<Choice> =
        callbackFlow {
            val receivedContent = AtomicBoolean()
            val timer =
                launch {
                    delay(firstTokenTimeoutMillis)
                    if (!receivedContent.get()) close(ChatException(ChatFailure.TIMEOUT))
                }

            fun complete() {
                if (receivedContent.get()) close() else close(ChatException(ChatFailure.EMPTY_RESPONSE))
            }

            val source =
                events.newEventSource(
                    request,
                    object : EventSourceListener() {
                        override fun onEvent(
                            eventSource: EventSource,
                            id: String?,
                            type: String?,
                            data: String,
                        ) {
                            if (data.trim() == "[DONE]") {
                                complete()
                                return
                            }
                            val chunk =
                                try {
                                    encoJson.decodeFromString<ChatChunk>(data)
                                } catch (_: SerializationException) {
                                    close(ChatException(ChatFailure.INVALID_RESPONSE))
                                    return
                                }
                            if (chunk.error != null || chunk.choices == null) {
                                close(ChatException(ChatFailure.INVALID_RESPONSE))
                                return
                            }
                            val choice = chunk.choices.firstOrNull { it.index == 0 } ?: return
                            val delta = choice.delta
                            val hasContent = !delta?.content.isNullOrEmpty()
                            val hasTool = conversation && !delta?.toolCalls.isNullOrEmpty()
                            if (hasContent || hasTool) receivedContent.set(true)
                            if (hasContent || hasTool || (conversation && !delta?.reasoning.isNullOrEmpty())) timer.cancel()
                            if ((conversation || hasContent) && trySend(choice).isFailure) {
                                close(ChatException(ChatFailure.INVALID_RESPONSE))
                            }
                        }

                        override fun onClosed(eventSource: EventSource) = complete()

                        override fun onFailure(
                            eventSource: EventSource,
                            t: Throwable?,
                            response: Response?,
                        ) {
                            val status = response?.code
                            val reason =
                                when (status) {
                                    401, 403 -> ChatFailure.UNAUTHORIZED
                                    404 -> ChatFailure.ENDPOINT
                                    429 -> ChatFailure.RATE_LIMITED
                                    null -> if (t is SocketTimeoutException) ChatFailure.TIMEOUT else ChatFailure.NETWORK
                                    in 200..299 -> if (t is IOException) ChatFailure.NETWORK else ChatFailure.INVALID_RESPONSE
                                    else -> ChatFailure.HTTP
                                }
                            close(ChatException(reason, status))
                        }
                    },
                )
            awaitClose {
                timer.cancel()
                source.cancel()
            }
        }.buffer(256)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<RequestMessage>,
        val stream: Boolean,
        val tools: List<ChatTool>? = null,
    )

    @Serializable
    private data class RequestMessage(
        val role: String,
        val content: JsonElement,
        @SerialName("tool_calls") val toolCalls: List<ChatToolCall>? = null,
        @SerialName("tool_call_id") val toolCallId: String? = null,
        @SerialName("reasoning_content") val reasoning: String? = null,
    )

    @Serializable
    private data class ChatTool(
        val type: String,
        val function: FunctionDefinition,
    )

    @Serializable
    private data class FunctionDefinition(
        val name: String,
        val description: String,
        val parameters: JsonObject,
    )

    @Serializable
    private data class ChatChunk(
        val choices: List<Choice>? = null,
        val error: JsonElement? = null,
    )

    @Serializable
    private data class Choice(
        val index: Int = 0,
        val delta: Delta? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    private data class Delta(
        val content: String? = null,
        @SerialName("reasoning_content") val reasoning: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolDelta>? = null,
    )

    @Serializable
    private data class ToolDelta(
        val index: Int,
        val id: String? = null,
        val type: String? = null,
        val function: FunctionDelta? = null,
    )

    @Serializable
    private data class FunctionDelta(
        val name: String? = null,
        val arguments: String? = null,
    )
}
