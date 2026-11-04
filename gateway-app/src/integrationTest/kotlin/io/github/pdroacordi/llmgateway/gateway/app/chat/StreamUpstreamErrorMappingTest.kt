package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient

private fun assertBeforeCommit(result: EntityExchangeResult<String>) {
    assertNull(result.responseHeaders.getFirst("cache-control"))
    assertNull(result.responseHeaders.getFirst("x-accel-buffering"))
    assertNull(result.responseHeaders.getFirst("x-gateway-deployment"))
}

/** GW-01's rows, as a stream: nothing commits before the first chunk, so the reply is the same by construction. */
class StreamUpstreamErrorMappingTest : MockBackedTest() {
    @ParameterizedTest(name = "{0}")
    @MethodSource(
        "io.github.pdroacordi.llmgateway.gateway.app.chat.UpstreamErrorMappingTest#clientRows",
        "io.github.pdroacordi.llmgateway.gateway.app.chat.UpstreamErrorMappingTest#serverRows",
    )
    fun `each upstream status before the first frame gives its row, with no stream header`(
        row: UpstreamErrorMappingTest.Row,
    ) {
        scenario("""{"otherwise":{"error":${row.error}}}""")

        val result = chat(STREAM).result()

        assertReply(result, row.expected)
        assertBeforeCommit(result)
        assertEquals(1, journal().size)
    }
}

@TestPropertySource(properties = ["llmgw.providers.openai.stream.first-byte-timeout=500ms"])
class StreamFirstByteDeadlineTest : MockBackedTest() {
    @Test
    fun `hang at first-byte-timeout gives 504, and the mock sees the call cancelled within 2 s`() {
        scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")

        val result = chat(STREAM).result()

        assertReply(result, TIMED_OUT)
        assertBeforeCommit(result)
        val requestId = checkNotNull(result.responseHeaders.getFirst("x-request-id"))
        eventually { journalOf(requestId).singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled" }
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClosedProviderPortStreamTest(
    @LocalServerPort private val port: Int,
) {
    @Test
    fun `a closed port gives 503 provider_unavailable`() {
        val result =
            WebTestClient
                .bindToServer()
                .baseUrl("http://127.0.0.1:$port")
                .build()
                .post()
                .uri(MockBackedTest.ROUTE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(MockBackedTest.STREAM)
                .exchange()
                .result()

        assertReply(result, UNAVAILABLE)
        assertBeforeCommit(result)
    }
}
