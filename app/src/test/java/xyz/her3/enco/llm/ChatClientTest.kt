package xyz.her3.enco.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
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
            .stream(ModelConfig(server.url("/v1/chat/completions"), "demo", "sample-key"), messages)

    private fun error(server: MockWebServer): ChatException =
        assertThrows(ChatException::class.java) { runBlocking { client(server).toList() } }

    private fun sse(body: String): MockResponse =
        MockResponse
            .Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(body)
            .build()

    private fun chunk(text: String): String = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$text\"}}]}\n\n"
}
