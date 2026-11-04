package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode

/** [MockBackedTest] with Anthropic on (GW-03 section 5): `mock-claude` reaches the mock's Messages route. */
@TestPropertySource(properties = ["llmgw.providers.anthropic.enabled=true"])
open class AnthropicBackedTest : MockBackedTest() {
    protected class Reply(
        val requestId: String,
        val body: JsonNode,
    )

    protected fun claudeScenario(json: String) = scenario(json, CLAUDE)

    /** A 200 from the gateway, read. */
    protected fun answer(body: String = CLAUDE_MINIMAL): Reply {
        val result = chat(body).result()
        assertEquals(200, result.status.value()) { result.responseBody }
        return Reply(
            checkNotNull(result.responseHeaders.getFirst("x-request-id")),
            mapper.readTree(result.responseBody),
        )
    }

    /** The body the mock received for this request. */
    protected fun upstreamBody(requestId: String): JsonNode =
        mapper.readTree(
            journalOf(requestId)
                .single()
                .at("/body")
                .stringValue(),
        )

    /** Every chunk of a raw stream, without `[DONE]`. */
    protected fun RawStream.chunks(): List<JsonNode> =
        frames
            .map { it.removePrefix("data: ") }
            .filter { it != "[DONE]" }
            .map { mapper.readTree(it) }

    /** `tool_calls` as a client would rebuild them from a stream, by `index`. */
    protected fun RawStream.toolCalls(): Map<Int, ToolCall> {
        val calls = sortedMapOf<Int, ToolCall>()
        chunks()
            .flatMap { it.at("/choices/0/delta/tool_calls").values() }
            .forEach { delta ->
                val index = delta.get("index").intValue()
                val call = calls.getOrPut(index) { ToolCall() }
                delta.get("id")?.let { call.id = it.stringValue() }
                delta.at("/function/name").takeUnless { it.isMissingNode }?.let { call.name = it.stringValue() }
                call.arguments.append(delta.at("/function/arguments").stringValue(""))
            }
        return calls
    }

    protected class ToolCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    companion object {
        const val CLAUDE = "mock-claude"
        const val CLAUDE_MINIMAL = """{"model":"mock-claude","messages":[{"role":"user","content":"Hi"}]}"""
        const val CLAUDE_STREAM =
            """{"model":"mock-claude","messages":[{"role":"user","content":"Hi"}],"stream":true}"""
    }
}
