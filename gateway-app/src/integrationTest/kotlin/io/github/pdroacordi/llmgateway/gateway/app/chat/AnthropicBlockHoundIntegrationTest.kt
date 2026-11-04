package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Real sockets through the Anthropic route, with BlockHound on, from a context of its own, so the first requests of the
 * translation path run here. A report from an event loop fails its request, so the status count is the check.
 */
@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(properties = ["llmgw.test.context=anthropic-blockhound"])
class AnthropicBlockHoundIntegrationTest : AnthropicBackedTest() {
    private val client by lazy {
        WebClient
            .builder()
            .baseUrl("http://127.0.0.1:$port")
            .defaultHeader("Authorization", TestKeys.BEARER)
            .build()
    }

    private fun call(index: Int): Mono<Int> {
        val body = if (index % 2 == 0) COMPLETE else STREAMED
        return client
            .post()
            .uri(ROUTE)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body.encodeToByteArray())
            .exchangeToMono { response -> response.releaseBody().thenReturn(response.statusCode().value()) }
    }

    @Test
    fun `2000 requests at concurrency 32, mixed complete and stream, pass BlockHound from the first`(
        output: CapturedOutput,
    ) {
        claudeScenario(
            """{"otherwise":{"content":{"mode":"fixed","text":"Hi there","thinking":[{"signature":"s"}],
            "toolCalls":[{"name":"get","input":{"a":1}}]}}}""",
        )

        val statuses =
            Flux
                .range(0, REQUESTS)
                .flatMap(::call, CONCURRENCY)
                .collectList()
                .block(BLOCK_LIMIT)
                .orEmpty()

        assertEquals(List(REQUESTS) { 200 }, statuses)
        assertFalse("BlockingOperationError" in output.all) { output.all }
    }

    private companion object {
        const val REQUESTS = 2000
        const val CONCURRENCY = 32
        val BLOCK_LIMIT: Duration = Duration.ofMinutes(3)
        const val COMPLETE =
            """{"model":"mock-claude","user":"someone","temperature":0.5,"tools":[{"type":"function",
            "function":{"name":"get"}}],"messages":[{"role":"system","content":"be brief"},
            {"role":"user","content":"Hi"}]}"""
        const val STREAMED =
            """{"model":"mock-claude","stream":true,"user":"someone","tools":[{"type":"function",
            "function":{"name":"get"}}],"messages":[{"role":"user","content":"Hi"}]}"""
    }
}
