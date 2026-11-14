package xyz.her3.enco.llm

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
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
    val content: String,
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
        )

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
        )

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
        )

    private fun streamRequest(
        config: ModelConfig,
        messages: List<RequestMessage>,
    ): Flow<String> {
        val payload = encoJson.encodeToString(ChatRequest(config.model, messages, stream = true))
        val request =
            Request
                .Builder()
                .url(config.endpoint)
                .header("Accept", "text/event-stream")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .apply { if (config.apiKey.isNotEmpty()) header("Authorization", "Bearer ${config.apiKey}") }
                .build()

        return readEvents(request).retryWhen { failure, attempt ->
            if (attempt == 0L && (failure as? ChatException)?.reason == ChatFailure.RATE_LIMITED) {
                delay(rateLimitDelayMillis)
                true
            } else {
                false
            }
        }
    }

    private fun readEvents(request: Request): Flow<String> =
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
                            val text =
                                chunk.choices
                                    .firstOrNull { it.index == 0 }
                                    ?.delta
                                    ?.content
                                    .orEmpty()
                            if (text.isEmpty()) return
                            receivedContent.set(true)
                            timer.cancel()
                            if (trySend(text).isFailure) close(ChatException(ChatFailure.INVALID_RESPONSE))
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
    )

    @Serializable
    private data class RequestMessage(
        val role: String,
        val content: JsonElement,
    )

    @Serializable
    private data class ChatChunk(
        val choices: List<Choice>? = null,
        val error: kotlinx.serialization.json.JsonElement? = null,
    )

    @Serializable
    private data class Choice(
        val index: Int = 0,
        val delta: Delta? = null,
    )

    @Serializable
    private data class Delta(
        val content: String? = null,
    )
}
