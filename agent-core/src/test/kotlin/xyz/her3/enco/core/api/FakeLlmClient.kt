package xyz.her3.enco.core.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeLlmClient(
    vararg replies: String,
) : LlmClient {
    private val replies = ArrayDeque(replies.toList())
    val requests = mutableListOf<List<LlmMessage>>()

    override fun stream(messages: List<LlmMessage>): Flow<String> =
        flow {
            requests += messages
            emit(replies.removeFirstOrNull() ?: error("没有预设的模型回复"))
        }
}
