package io.github.pdroacordi.llmgateway.mockprovider.admin

import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Behaviour
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ContentMode
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ContentSpec
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Fault
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.FaultKind
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.InjectedError
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Latency
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Scenario
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Step
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.UsageSpec
import io.github.pdroacordi.llmgateway.mockprovider.error.reject
import io.github.pdroacordi.llmgateway.mockprovider.web.member
import io.github.pdroacordi.llmgateway.mockprovider.web.optBoolean
import io.github.pdroacordi.llmgateway.mockprovider.web.optInt
import io.github.pdroacordi.llmgateway.mockprovider.web.optLong
import io.github.pdroacordi.llmgateway.mockprovider.web.optString
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * The `Scenario` JSON of `/__mock/scenarios` (FND-03 section 4). Strict: an unknown member, a value out of range or a
 * wrong type is a 400 whose `param` is the member's path (`steps[0].error.status`), so a typo in a test fails loudly.
 */
class ScenarioJson(
    mapper: JsonMapper,
    private val maxHoldMs: Long,
) {
    private val writer = ScenarioJsonWriter(mapper)

    fun parse(node: JsonNode): Scenario {
        val root = obj(node, "scenario", setOf("steps", "otherwise"), prefix = "")
        val steps =
            root.member("steps")?.let { array ->
                if (!array.isArray || array.size() > MAX_STEPS) {
                    reject("steps", "'steps' must be an array of at most $MAX_STEPS steps.")
                }
                array.values().mapIndexed { index, step -> step(step, "steps[$index]") }
            }
        return Scenario(
            steps = steps.orEmpty(),
            otherwise = root.member("otherwise")?.let { behaviour(it, "otherwise", BEHAVIOUR_MEMBERS) },
        )
    }

    /** The JSON form `GET /__mock/scenarios` shows; [parse] reads it back unchanged. */
    fun write(scenario: Scenario): ObjectNode = writer.write(scenario)

    private fun step(
        node: JsonNode,
        path: String,
    ): Step {
        val behaviour = behaviour(node, path, BEHAVIOUR_MEMBERS + "times")
        val times = node.optInt("times", 1..MAX_TIMES, "$path.times") ?: 1
        return Step(behaviour, times)
    }

    private fun behaviour(
        node: JsonNode,
        path: String,
        allowed: Set<String>,
    ): Behaviour {
        obj(node, path, allowed, "$path.")
        val error = node.member("error")?.let { error(it, "$path.error") }
        val fault = node.member("fault")?.let { fault(it, "$path.fault") }
        if (error != null && fault != null && fault.kind != FaultKind.STREAM_ERROR) {
            reject("$path.fault", "'error' and 'fault' together are allowed only for fault kind stream_error.")
        }
        return Behaviour(
            latency = node.member("latency")?.let { latency(it, "$path.latency") },
            error = error,
            fault = fault,
            content = node.member("content")?.let { content(it, "$path.content") },
            usage = node.member("usage")?.let { usage(it, "$path.usage") },
        )
    }

    private fun latency(
        node: JsonNode,
        path: String,
    ): Latency {
        obj(node, path, setOf("ttftMs", "perTokenMs", "jitterMs"), "$path.")
        val range = 0..maxHoldMs
        return Latency(
            ttftMs = node.optLong("ttftMs", range, "$path.ttftMs"),
            perTokenMs = node.optLong("perTokenMs", range, "$path.perTokenMs"),
            jitterMs = node.optLong("jitterMs", range, "$path.jitterMs"),
        )
    }

    private fun error(
        node: JsonNode,
        path: String,
    ): InjectedError {
        obj(node, path, ERROR_MEMBERS, "$path.")
        val headers =
            node.member("headers")?.let { headers ->
                if (!headers.isObject) reject("$path.headers", "'$path.headers' must be an object of strings.")
                headers.properties().associate { (name, value) ->
                    val param = "$path.headers.$name"
                    // Netty validates only when the error is written, which would turn a typo into a 500 per request.
                    if (!TOKEN.matches(name)) reject(param, "'$param' must be an HTTP field name (RFC 9110 token).")
                    if (!value.isString || value.stringValue().any { it in FORBIDDEN_IN_VALUE }) {
                        reject(param, "'$param' must be a string without CR, LF or NUL.")
                    }
                    name to value.stringValue()
                }
            }
        return InjectedError(
            status =
                node.optInt(
                    "status",
                    STATUSES,
                    "$path.status",
                ) ?: reject("$path.status", "'$path.status' is required."),
            type = node.optString("type", "$path.type"),
            code = node.optString("code", "$path.code"),
            message = node.optString("message", "$path.message"),
            param = node.optString("param", "$path.param"),
            retryAfterSeconds = node.optLong("retryAfterSeconds", 0..Long.MAX_VALUE, "$path.retryAfterSeconds"),
            retryAfterMs = node.optLong("retryAfterMs", 0..Long.MAX_VALUE, "$path.retryAfterMs"),
            headers = headers.orEmpty(),
        )
    }

    private fun fault(
        node: JsonNode,
        path: String,
    ): Fault {
        obj(node, path, setOf("kind", "afterPieces"), "$path.")
        val wire = node.optString("kind", "$path.kind") ?: reject("$path.kind", "'$path.kind' is required.")
        val kind =
            FaultKind.fromWire(wire)
                ?: reject("$path.kind", "'$path.kind' must be one of ${FaultKind.entries.joinToString { it.wire }}.")
        return Fault(kind, node.optInt("afterPieces", 0..Int.MAX_VALUE, "$path.afterPieces") ?: 0)
    }

    private fun content(
        node: JsonNode,
        path: String,
    ): ContentSpec {
        obj(node, path, setOf("mode", "text", "completionTokens"), "$path.")
        val mode =
            node.optString("mode", "$path.mode")?.let { wire ->
                ContentMode.fromWire(wire)
                    ?: reject(
                        "$path.mode",
                        "'$path.mode' must be one of ${ContentMode.entries.joinToString { it.wire }}.",
                    )
            }
        val text = node.optString("text", "$path.text")
        if (mode == ContentMode.FIXED && text == null) reject("$path.text", "'$path.text' is required with mode fixed.")
        return ContentSpec(mode, text, node.optInt("completionTokens", COMPLETION_TOKENS, "$path.completionTokens"))
    }

    private fun usage(
        node: JsonNode,
        path: String,
    ): UsageSpec {
        obj(
            node,
            path,
            setOf("omit", "cachedTokens", "cacheCreationTokens", "promptTokens", "completionTokens"),
            "$path.",
        )
        val counts = 0..Int.MAX_VALUE
        return UsageSpec(
            omit = node.optBoolean("omit", "$path.omit"),
            cachedTokens = node.optInt("cachedTokens", counts, "$path.cachedTokens"),
            cacheCreationTokens = node.optInt("cacheCreationTokens", counts, "$path.cacheCreationTokens"),
            promptTokens = node.optInt("promptTokens", counts, "$path.promptTokens"),
            completionTokens = node.optInt("completionTokens", counts, "$path.completionTokens"),
        )
    }

    /** [node] must be an object whose members are all in [allowed]; an unknown one is named as [prefix] + name. */
    private fun obj(
        node: JsonNode,
        path: String,
        allowed: Set<String>,
        prefix: String,
    ): JsonNode {
        if (!node.isObject) reject(path, "'$path' must be a JSON object.")
        node.propertyNames().firstOrNull { it !in allowed }?.let {
            reject(
                "$prefix$it",
                "Unknown member '$prefix$it'.",
            )
        }
        return node
    }

    private companion object {
        val BEHAVIOUR_MEMBERS = setOf("latency", "error", "fault", "content", "usage")
        val ERROR_MEMBERS =
            setOf("status", "type", "code", "message", "param", "retryAfterSeconds", "retryAfterMs", "headers")
        val STATUSES = 400..599

        // Steps are stored unexpanded; the caps keep a typo from becoming a huge scenario (FND-03 systems entry).
        const val MAX_STEPS = 1000
        const val MAX_TIMES = 1_000_000

        // RFC 9110 section 5.6.2.
        val TOKEN = Regex("""[!#$%&'*+\-.^_`|~0-9A-Za-z]+""")
        val FORBIDDEN_IN_VALUE = setOf('\r', '\n', '\u0000')
        val COMPLETION_TOKENS = 1..4096
    }
}
