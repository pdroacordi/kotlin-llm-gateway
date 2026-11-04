package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private fun route(model: String): String = """{"model":"$model","messages":[{"role":"user","content":"Hi"}]}"""

class ProviderSelectionTest : AnthropicBackedTest() {
    private fun routeOf(model: String): Pair<String?, String> {
        val result = chat(route(model)).result()
        val requestId = checkNotNull(result.responseHeaders.getFirst("x-request-id"))
        return result.responseHeaders.getFirst("x-gateway-deployment") to
            journalOf(requestId).single().at("/route").stringValue()
    }

    @Test
    fun `claude-x and mock-claude go to Anthropic, mock-chat stays on OpenAI`() {
        assertEquals("anthropic" to "anthropic.messages", routeOf("claude-x"))
        assertEquals("anthropic" to "anthropic.messages", routeOf("mock-claude"))
        assertEquals("openai" to "openai.chat", routeOf("mock-chat"))
    }
}

class ProviderSelectionOffTest : MockBackedTest() {
    @Test
    fun `with Anthropic off, claude-x reaches the OpenAI upstream as before`() {
        val result = chat(route("claude-x")).result()
        val requestId = checkNotNull(result.responseHeaders.getFirst("x-request-id"))

        assertEquals("openai", result.responseHeaders.getFirst("x-gateway-deployment"))
        assertEquals("openai.chat", journalOf(requestId).single().at("/route").stringValue())
    }
}
