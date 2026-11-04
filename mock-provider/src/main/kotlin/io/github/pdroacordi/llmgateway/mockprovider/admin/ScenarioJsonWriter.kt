package io.github.pdroacordi.llmgateway.mockprovider.admin

import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Behaviour
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ContentSpec
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.InjectedError
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Latency
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Scenario
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ToolCallSpec
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.UsageSpec
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/** A scenario back to its JSON form: only the members that are set, in the order `ScenarioJson` documents. */
internal class ScenarioJsonWriter(
    private val mapper: JsonMapper,
) {
    fun write(scenario: Scenario): ObjectNode {
        val root = mapper.createObjectNode()
        val steps = root.putArray("steps")
        scenario.steps.forEach { step -> behaviour(steps.addObject().put("times", step.times), step.behaviour) }
        scenario.otherwise?.let { behaviour(root.putObject("otherwise"), it) }
        return root
    }

    private fun behaviour(
        node: ObjectNode,
        behaviour: Behaviour,
    ) {
        behaviour.latency?.let { latency(node.putObject("latency"), it) }
        behaviour.error?.let { error(node.putObject("error"), it) }
        behaviour.fault?.let { node.putObject("fault").put("kind", it.kind.wire).put("afterPieces", it.afterPieces) }
        behaviour.content?.let { content(node.putObject("content"), it) }
        behaviour.usage?.let { usage(node.putObject("usage"), it) }
    }

    private fun latency(
        target: ObjectNode,
        latency: Latency,
    ) {
        latency.ttftMs?.let { target.put("ttftMs", it) }
        latency.perTokenMs?.let { target.put("perTokenMs", it) }
        latency.jitterMs?.let { target.put("jitterMs", it) }
    }

    private fun error(
        target: ObjectNode,
        error: InjectedError,
    ) {
        target.put("status", error.status)
        error.type?.let { target.put("type", it) }
        error.code?.let { target.put("code", it) }
        error.message?.let { target.put("message", it) }
        error.param?.let { target.put("param", it) }
        error.retryAfterSeconds?.let { target.put("retryAfterSeconds", it) }
        error.retryAfterMs?.let { target.put("retryAfterMs", it) }
        val headers = target.putObject("headers")
        error.headers.forEach { (name, value) -> headers.put(name, value) }
    }

    private fun content(
        target: ObjectNode,
        content: ContentSpec,
    ) {
        content.mode?.let { target.put("mode", it.wire) }
        content.text?.let { target.put("text", it) }
        content.completionTokens?.let { target.put("completionTokens", it) }
        content.toolCalls?.let { calls ->
            val array = target.putArray("toolCalls")
            calls.forEach { toolCall(array.addObject(), it) }
        }
        content.finish?.let { target.put("finish", it.wire) }
        content.thinking?.let { blocks ->
            val array = target.putArray("thinking")
            blocks.forEach { block ->
                val item = array.addObject()
                block.signature?.let { item.put("signature", it) }
                block.redacted?.let { item.put("redacted", it) }
            }
        }
    }

    private fun toolCall(
        target: ObjectNode,
        call: ToolCallSpec,
    ) {
        call.id?.let { target.put("id", it) }
        target.put("name", call.name)
        target.set("input", mapper.readTree(call.inputJson))
        call.chunkChars?.let { target.put("chunkChars", it) }
    }

    private fun usage(
        target: ObjectNode,
        usage: UsageSpec,
    ) {
        usage.omit?.let { target.put("omit", it) }
        usage.cachedTokens?.let { target.put("cachedTokens", it) }
        usage.cacheCreationTokens?.let { target.put("cacheCreationTokens", it) }
        usage.promptTokens?.let { target.put("promptTokens", it) }
        usage.completionTokens?.let { target.put("completionTokens", it) }
    }
}
