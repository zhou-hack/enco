package xyz.her3.enco.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.her3.enco.core.api.LlmMessage
import xyz.her3.enco.core.api.LlmPart
import xyz.her3.enco.core.api.LlmRole
import xyz.her3.enco.core.protocol.encoJson

class ChatLlmClientTest {
    @Test
    fun mapsMultipleTextAndImageMessagesToOpenAiContent() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(sse(chunk("OK")))
            val image = "data:image/jpeg;base64,dGVzdA=="
            val client =
                ChatLlmClient(
                    ModelConfig(server.url("/v1/chat/completions"), "demo", "sample-key"),
                    ChatClient(rateLimitDelayMillis = 0),
                )
            val reply =
                runBlocking {
                    client
                        .stream(
                            listOf(
                                LlmMessage(LlmRole.SYSTEM, "只返回结果"),
                                LlmMessage(
                                    LlmRole.USER,
                                    listOf(
                                        LlmPart.Text("先看这张截图"),
                                        LlmPart.Image(image),
                                        LlmPart.Text("再说明下一步"),
                                    ),
                                ),
                                LlmMessage(LlmRole.ASSISTANT, "好的"),
                            ),
                        ).toList()
                }

            assertEquals(listOf("OK"), reply)
            val request = server.takeRequest()
            assertEquals("Bearer sample-key", request.headers["Authorization"])
            val messages =
                encoJson
                    .parseToJsonElement(request.body!!.utf8())
                    .jsonObject
                    .getValue("messages")
                    .jsonArray
            assertEquals(
                listOf("system", "user", "assistant"),
                messages.map {
                    it.jsonObject
                        .getValue("role")
                        .jsonPrimitive.content
                },
            )
            assertEquals(
                "只返回结果",
                messages[0]
                    .jsonObject
                    .getValue("content")
                    .jsonPrimitive.content,
            )
            val parts = messages[1].jsonObject.getValue("content").jsonArray
            assertEquals(3, parts.size)
            assertEquals(
                "text",
                parts[0]
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "先看这张截图",
                parts[0]
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "image_url",
                parts[1]
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive.content,
            )
            assertEquals(
                image,
                parts[1]
                    .jsonObject
                    .getValue("image_url")
                    .jsonObject
                    .getValue("url")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "再说明下一步",
                parts[2]
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "好的",
                messages[2]
                    .jsonObject
                    .getValue("content")
                    .jsonPrimitive.content,
            )
        }
    }

    private fun sse(body: String): MockResponse =
        MockResponse
            .Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(body)
            .build()

    private fun chunk(text: String): String = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$text\"}}]}\n\n"
}
