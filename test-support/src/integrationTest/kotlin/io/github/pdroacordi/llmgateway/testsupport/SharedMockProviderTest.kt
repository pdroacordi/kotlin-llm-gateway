package io.github.pdroacordi.llmgateway.testsupport

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class SharedMockProviderTest {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @BeforeEach
    fun reset() = SharedMockProvider.reset()

    @Test
    fun `the container reports health UP`() {
        val response = http.send(request("/actuator/health").GET().build(), HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode())
        assertTrue("\"status\":\"UP\"" in response.body()) { response.body() }
    }

    @Test
    fun `the container answers a chat completion`() {
        val body = """{"model":"mock-chat","messages":[{"role":"user","content":"Hello from test-support"}]}"""
        val response =
            http.send(
                request("/v1/chat/completions")
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer sk-test-support")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        assertEquals(200, response.statusCode(), response.body())
        assertTrue("\"object\":\"chat.completion\"" in response.body()) { response.body() }
    }

    @Test
    fun `reset succeeds`() {
        assertDoesNotThrow { SharedMockProvider.reset() }
    }

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create("${SharedMockProvider.baseUrl}$path"))
            .timeout(Duration.ofSeconds(10))
}
