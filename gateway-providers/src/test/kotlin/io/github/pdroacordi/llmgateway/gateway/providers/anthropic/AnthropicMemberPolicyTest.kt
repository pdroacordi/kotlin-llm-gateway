package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper

class AnthropicMemberPolicyTest {
    private val mapper = JsonMapper.builder().build()

    private fun extras(json: String): JsonValue.Object = mapper.readTree(json).toJsonValue() as JsonValue.Object

    @Test
    fun `every member of the OpenAI reference has a rule of its own`() {
        val missing = OPENAI_MEMBERS.filter { it !in AnthropicMemberPolicy.rules }

        assertTrue(missing.isEmpty()) { missing.toString() }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "model", "messages", "stream", "max_completion_tokens", "max_tokens", "stop", "tools", "tool_choice",
            "parallel_tool_calls", "safety_identifier", "user",
        ],
    )
    fun `the writer owns these`(member: String) {
        assertEquals(MemberRule.Translated, AnthropicMemberPolicy.ruleOf(member))
    }

    @ParameterizedTest
    @ValueSource(strings = ["temperature", "top_p", "top_k", "cache_control"])
    fun `these are forwarded`(member: String) {
        assertEquals(MemberRule.Forward, AnthropicMemberPolicy.ruleOf(member))
        assertNull(AnthropicMemberPolicy.refusal(extras("""{"$member":0.5}""")))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "stream_options", "metadata", "store", "prompt_cache_key", "prompt_cache_options", "prompt_cache_retention",
        ],
    )
    fun `these are ignored whatever their value`(member: String) {
        assertEquals(MemberRule.Ignore, AnthropicMemberPolicy.ruleOf(member))
        assertNull(AnthropicMemberPolicy.refusal(extras("""{"$member":{"a":1}}""")))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"service_tier":"auto"}""",
            """{"service_tier":"default"}""",
            """{"n":1}""",
            """{"n":1.0}""",
            """{"logprobs":false}""",
            """{"frequency_penalty":0}""",
            """{"frequency_penalty":0.0}""",
            """{"presence_penalty":0}""",
            """{"presence_penalty":0.0}""",
            """{"modalities":["text"]}""",
        ],
    )
    fun `a neutral value counts as absent`(json: String) {
        assertNull(AnthropicMemberPolicy.refusal(extras(json)))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"service_tier":"flex"} | service_tier""",
            """{"service_tier":"priority"} | service_tier""",
            """{"n":2} | n""",
            """{"logprobs":true} | logprobs""",
            """{"frequency_penalty":0.5} | frequency_penalty""",
            """{"presence_penalty":-1} | presence_penalty""",
            """{"modalities":["text","audio"]} | modalities""",
        ],
    )
    fun `any other value is unsupported_value`(
        json: String,
        param: String,
    ) {
        assertEquals(
            ProviderFailure.UnsupportedByProvider("unsupported_value", param),
            AnthropicMemberPolicy.refusal(extras(json)),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "top_logprobs", "seed", "logit_bias", "response_format", "audio", "prediction", "moderation",
            "reasoning_effort", "verbosity", "web_search_options", "functions", "function_call", "thinking",
            "output_config", "something_new",
        ],
    )
    fun `these and anything unlisted are unsupported_parameter`(member: String) {
        assertEquals(
            ProviderFailure.UnsupportedByProvider("unsupported_parameter", member),
            AnthropicMemberPolicy.refusal(extras("""{"$member":{"type":"text"}}""")),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["seed", "something_new", "n", "logprobs"])
    fun `a member set to null counts as absent`(member: String) {
        assertNull(AnthropicMemberPolicy.refusal(extras("""{"$member":null}""")))
    }

    @Test
    fun `a member name that fails the character rule refuses with no param`() {
        assertEquals(
            ProviderFailure.UnsupportedByProvider("unsupported_parameter", null),
            AnthropicMemberPolicy.refusal(extras("""{"bad name!":1}""")),
        )
    }

    private companion object {
        // developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create, 2026-11-04.
        val OPENAI_MEMBERS =
            listOf(
                "messages",
                "model",
                "audio",
                "frequency_penalty",
                "function_call",
                "functions",
                "logit_bias",
                "logprobs",
                "max_completion_tokens",
                "max_tokens",
                "metadata",
                "modalities",
                "moderation",
                "n",
                "parallel_tool_calls",
                "prediction",
                "presence_penalty",
                "prompt_cache_key",
                "prompt_cache_options",
                "prompt_cache_retention",
                "reasoning_effort",
                "response_format",
                "safety_identifier",
                "seed",
                "service_tier",
                "stop",
                "store",
                "stream",
                "stream_options",
                "temperature",
                "tool_choice",
                "tools",
                "top_logprobs",
                "top_p",
                "user",
                "verbosity",
                "web_search_options",
            )
    }
}
