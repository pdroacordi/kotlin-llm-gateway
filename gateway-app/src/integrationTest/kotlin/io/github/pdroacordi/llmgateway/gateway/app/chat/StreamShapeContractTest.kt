package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.github.pdroacordi.llmgateway.testsupport.SharedMockProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** The gateway adds nothing to a stream and removes nothing, event by event; order is not compared (RFC 8259). */
class StreamShapeContractTest : MockBackedTest() {
    private fun direct(body: String): List<String> {
        val text =
            WebTestClient
                .bindToServer()
                .baseUrl(SharedMockProvider.baseUrl)
                .build()
                .post()
                .uri(ROUTE)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", TestKeys.BEARER)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        return checkNotNull(text).split("\n\n").filter { it.isNotEmpty() }
    }

    private fun normalised(frame: String): JsonNode {
        val tree = mapper.readTree(frame.removePrefix("data: ")) as ObjectNode
        tree.put("id", "normalised")
        tree.put("created", 0)
        return tree
    }

    private fun assertSameEvents(
        direct: List<String>,
        viaGateway: List<String>,
    ) {
        assertEquals(direct.size, viaGateway.size) { "$direct\n$viaGateway" }
        assertEquals("data: [DONE]", direct.last())
        assertEquals("data: [DONE]", viaGateway.last())
        direct.dropLast(1).zip(viaGateway.dropLast(1)).forEach { (expected, actual) ->
            assertEquals(normalised(expected), normalised(actual))
        }
    }

    @Test
    fun `with include_usage the events equal the mock's, ending in the usage chunk and DONE`() {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi there, friend"}}}""")

        assertSameEvents(direct(asking()), rawStream(asking()).frames)
    }

    @Test
    fun `without include_usage the events equal the mock's`() {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi there, friend"}}}""")

        assertSameEvents(direct(STREAM), rawStream().frames)
    }
}
