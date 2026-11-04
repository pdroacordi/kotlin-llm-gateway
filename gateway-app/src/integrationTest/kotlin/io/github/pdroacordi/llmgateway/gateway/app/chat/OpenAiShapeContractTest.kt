package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.testsupport.SharedMockProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** The gateway adds nothing to a completion and removes nothing: the same body as the mock, member for member. */
class OpenAiShapeContractTest : MockBackedTest() {
    private fun direct(): String =
        checkNotNull(
            WebTestClient
                .bindToServer()
                .baseUrl(SharedMockProvider.baseUrl)
                .build()
                .post()
                .uri(ROUTE)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer client-key")
                .bodyValue(MINIMAL)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody,
        )

    private fun viaGateway(): String =
        checkNotNull(
            chat()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody,
        )

    // Replaced in place, so a difference in member order still shows.
    private fun normalised(body: String): String {
        val tree = mapper.readTree(body) as ObjectNode
        tree.put("id", "normalised")
        tree.put("created", 0)
        return mapper.writeValueAsString(tree)
    }

    private fun assertHas(
        tree: JsonNode,
        vararg pointers: String,
    ) {
        pointers.forEach { assertTrue(!tree.at(it).isMissingNode) { "$it is missing from $tree" } }
    }

    @Test
    fun `the gateway's body equals the mock's after normalising id and created`() {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi there"}}}""")

        assertEquals(normalised(direct()), normalised(viaGateway()))
    }

    @Test
    fun `every member OpenAI's reference requires is present`() {
        val tree = mapper.readTree(viaGateway())

        assertHas(tree, "/id", "/object", "/created", "/model", "/choices", "/usage")
        assertHas(tree, "/choices/0/index", "/choices/0/message", "/choices/0/logprobs", "/choices/0/finish_reason")
        assertHas(tree, "/choices/0/message/role", "/choices/0/message/content", "/choices/0/message/refusal")
        assertHas(tree, "/usage/prompt_tokens", "/usage/completion_tokens", "/usage/total_tokens")
        assertEquals("chat.completion", tree.get("object").stringValue())
    }
}
