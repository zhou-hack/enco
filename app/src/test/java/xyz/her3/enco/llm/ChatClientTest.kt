package xyz.her3.enco.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.her3.enco.core.agent.PhoneTaskKind
import xyz.her3.enco.core.agent.PhoneTaskRequest
import xyz.her3.enco.core.agent.PhoneTaskTool
import xyz.her3.enco.core.protocol.encoJson

class ChatClientTest {
    private val messages = listOf(ChatMessage("user", "你好"))

    @Test
    fun sendsStreamingRequestAndReadsChunks() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(sse(chunk("你") + chunk("好") + "data: [DONE]\n\n"))
            val parts = runBlocking { client(server).toList() }
            assertEquals(listOf("你", "好"), parts)
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.url.encodedPath)
            assertEquals("Bearer sample-key", request.headers["Authorization"])
            val json = encoJson.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals("true", json.getValue("stream").jsonPrimitive.content)
            assertEquals("demo", json.getValue("model").jsonPrimitive.content)
        }
    }

    @Test
    fun answersOrdinaryChatInTheSingleRequestThatAdvertisesThePhoneTool() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(sse(chunk("你") + chunk("好") + "data: [DONE]\n\n"))
            val text = StringBuilder()
            val response = runBlocking { ChatClient().chat(config(server), messages, ChatTool.PHONE_CONTROL) { text.append(it) } }
            assertEquals("你好", text.toString())
            assertEquals("你好", response.content)
            assertNull(response.toolCalls)
            assertEquals(1, server.requestCount)
            val request = encoJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            val function =
                request
                    .getValue("tools")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("function")
                    .jsonObject
            assertEquals(PhoneTaskTool.NAME, function.getValue("name").jsonPrimitive.content)
            assertEquals(PhoneTaskTool.parameters, function.getValue("parameters"))
        }
    }

    @Test
    fun joinsToolArgumentFragmentsAndReturnsTheResultWithTheMatchingId() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(sse(toolStream + toolFinish))
            server.enqueue(sse(chunk("完成")))
            runBlocking {
                val client = ChatClient()
                val callMessage = client.chat(config(server), messages, ChatTool.PHONE_CONTROL) { error("工具参数不应显示为正文") }
                val call = checkNotNull(callMessage.toolCalls).single()
                assertEquals(
                    PhoneTaskRequest(PhoneTaskKind.SETTINGS, "打开蓝牙设置"),
                    PhoneTaskTool.parse(call.function.name, call.function.arguments),
                )
                val toolResult = ChatMessage("tool", """{"status":"已完成"}""", toolCallId = call.id)
                assertEquals("完成", client.chat(config(server), messages + callMessage + toolResult, null) {}.content)
            }
            server.takeRequest()
            val followUp = encoJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertNull(followUp["tools"])
            val (_, assistant, result) = followUp.getValue("messages").jsonArray.map { it.jsonObject }
            assertEquals(JsonNull, assistant.getValue("content"))
            assertEquals("检查请求", assistant.getValue("reasoning_content").jsonPrimitive.content)
            assertEquals(
                "call_phone",
                assistant
                    .getValue("tool_calls")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content,
            )
            assertEquals("tool", result.getValue("role").jsonPrimitive.content)
            assertEquals("call_phone", result.getValue("tool_call_id").jsonPrimitive.content)
        }
    }

    @Test
    fun rejectsIncompleteParallelOrUnsolicitedToolCalls() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(sse(toolStream + "data: [DONE]\n\n"))
            server.enqueue(
                sse(toolStream + """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1}]}}]}""" + "\n\n" + toolFinish),
            )
            server.enqueue(sse(toolStream + toolFinish))
            val client = ChatClient()
            val toolResult = ChatMessage("tool", "已完成", toolCallId = "previous")
            val scenarios =
                listOf(
                    ChatTool.PHONE_CONTROL to messages,
                    ChatTool.PHONE_CONTROL to messages,
                    null to (messages + toolResult),
                )
            for ((tool, history) in scenarios) {
                val error =
                    assertThrows(ChatException::class.java) {
                        runBlocking { client.chat(config(server), history, tool) {} }
                    }
                assertEquals(ChatFailure.INVALID_RESPONSE, error.reason)
            }
        }
    }

    @Test
    fun onlyAllowsTheResultNavigationToolDuringResultHandling() {
        MockWebServer().use { server ->
            server.start()
            val resultCall =
                """
                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_result","type":"function","function":{"name":"open_task_result","arguments":"{}"}}]}}]}
                """.trimIndent()
            server.enqueue(sse(resultCall + "\n\n" + toolFinish))
            server.enqueue(sse(toolStream + toolFinish))
            val client = ChatClient()
            val call = runBlocking { client.chat(config(server), messages, ChatTool.OPEN_TASK_RESULT) {} }.toolCalls?.single()
            assertEquals("open_task_result", call?.function?.name)
            val body = encoJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            val function =
                body
                    .getValue("tools")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("function")
                    .jsonObject
            assertEquals("open_task_result", function.getValue("name").jsonPrimitive.content)
            val error =
                assertThrows(ChatException::class.java) {
                    runBlocking { client.chat(config(server), messages, ChatTool.OPEN_TASK_RESULT) {} }
                }
            assertEquals(ChatFailure.INVALID_RESPONSE, error.reason)
        }
    }

    @Test
    fun sendsAnImageAlongsideTheVisionPrompt() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(sse(chunk("OK")))
            val config = ModelConfig(server.url("/v1/chat/completions"), "demo", "")
            val image = "data:image/jpeg;base64,dGVzdA=="
            val reply = runBlocking { ChatClient().streamImage(config, "system", "look", image).toList() }
            assertEquals(listOf("OK"), reply)
            val request = encoJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            val parts =
                request
                    .getValue("messages")
                    .jsonArray[1]
                    .jsonObject
                    .getValue("content")
                    .jsonArray
            assertEquals(
                "look",
                parts[0]
                    .jsonObject
                    .getValue("text")
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
        }
    }

    @Test
    fun acceptsEofAfterContentAndRejectsEmptyResponse() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(sse(chunk("OK")))
            assertEquals(listOf("OK"), runBlocking { client(server).toList() })
            server.enqueue(sse("data: [DONE]\n\n"))
            assertEquals(ChatFailure.EMPTY_RESPONSE, error(server).reason)
        }
    }

    @Test
    fun rejectsMalformedEventsAndReportsUnauthorized() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(sse("data: {}\n\n"))
            assertEquals(ChatFailure.INVALID_RESPONSE, error(server).reason)
            server.enqueue(MockResponse.Builder().code(401).build())
            assertEquals(ChatFailure.UNAUTHORIZED, error(server).reason)
        }
    }

    @Test
    fun retriesRateLimitOnce() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(429).build())
            server.enqueue(sse(chunk("OK")))
            assertEquals(listOf("OK"), runBlocking { client(server).toList() })
            assertEquals(2, server.requestCount)
            server.enqueue(MockResponse.Builder().code(429).build())
            server.enqueue(MockResponse.Builder().code(429).build())
            assertEquals(ChatFailure.RATE_LIMITED, error(server).reason)
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun rejectsCredentialsInUrlAndAllowsLoopbackHttp() {
        assertNull(chatEndpoint("https://secret@example.org/v1"))
        assertNull(chatEndpoint("https://example.org/v1?key=secret"))
        assertNull(chatEndpoint("http://example.org/v1"))
        assertEquals("/v1/chat/completions", chatEndpoint("http://localhost:1234/v1/")?.encodedPath)
    }

    private fun client(server: MockWebServer) =
        ChatClient(rateLimitDelayMillis = 0)
            .stream(config(server), messages)

    private fun config(server: MockWebServer) = ModelConfig(server.url("/v1/chat/completions"), "demo", "sample-key")

    private fun error(server: MockWebServer): ChatException =
        assertThrows(ChatException::class.java) { runBlocking { client(server).toList() } }

    private fun sse(body: String): MockResponse =
        MockResponse
            .Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(body)
            .build()

    private fun chunk(text: String): String = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$text\"}}]}\n\n"

    private val toolStream =
        """
        data: {"choices":[{"index":0,"delta":{"reasoning_content":"检查请求"}}]}

        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_phone","type":"function","function":{"name":"phone_control","arguments":"{\"kind\":\"settings\","}}]}}]}

        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"goal\":\"打开蓝牙设置\"}"}}]}}]}
        """.trimIndent() + "\n\n"

    private val toolFinish = """data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""" + "\n\ndata: [DONE]\n\n"
}
