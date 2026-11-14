package xyz.her3.enco.core.api

import kotlinx.coroutines.flow.Flow

interface LlmClient {
    fun stream(messages: List<LlmMessage>): Flow<String>
}

enum class LlmRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

sealed interface LlmPart {
    data class Text(
        val text: String,
    ) : LlmPart

    data class Image(
        val url: String,
    ) : LlmPart
}

data class LlmMessage(
    val role: LlmRole,
    val parts: List<LlmPart>,
) {
    constructor(
        role: LlmRole,
        text: String,
    ) : this(role, listOf(LlmPart.Text(text)))
}
