package xyz.her3.enco.llm

import kotlinx.coroutines.flow.Flow
import xyz.her3.enco.core.api.LlmClient
import xyz.her3.enco.core.api.LlmMessage

internal class ChatLlmClient(
    private val config: ModelConfig,
    private val client: ChatClient = ChatClient(),
) : LlmClient {
    override fun stream(messages: List<LlmMessage>): Flow<String> = client.streamLlm(config, messages)
}
