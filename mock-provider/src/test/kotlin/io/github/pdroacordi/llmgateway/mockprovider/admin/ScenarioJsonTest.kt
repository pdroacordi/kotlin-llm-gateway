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
import io.github.pdroacordi.llmgateway.mockprovider.error.MockRejection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper

class ScenarioJsonTest {
    private val mapper = JsonMapper.builder().build()
    private val json = ScenarioJson(mapper, maxHoldMs = 300_000)

    @Test
    fun `a full scenario parses`() {
        val scenario =
            json.parse(
                tree(
                    """
                    {"steps":[{"times":2,"error":{"status":429,"retryAfterSeconds":2,"retryAfterMs":2000,"type":"t",
                      "code":"c","message":"m","param":"p","headers":{"x-ratelimit-remaining-requests":"0"}}},
                      {"fault":{"kind":"stream_error","afterPieces":3},"error":{"status":529}},
                      {"latency":{"ttftMs":800,"perTokenMs":5,"jitterMs":1},"content":{"mode":"fixed","text":"hi",
                       "completionTokens":4},"usage":{"omit":true,"cachedTokens":1,"cacheCreationTokens":2,
                       "promptTokens":3,"completionTokens":4}}],
                     "otherwise":{"fault":{"kind":"hang"}}}
                    """,
                ),
            )

        assertEquals(
            Scenario(
                steps =
                    listOf(
                        Step(
                            Behaviour(
                                error =
                                    InjectedError(
                                        status = 429,
                                        type = "t",
                                        code = "c",
                                        message = "m",
                                        param = "p",
                                        retryAfterSeconds = 2,
                                        retryAfterMs = 2000,
                                        headers = mapOf("x-ratelimit-remaining-requests" to "0"),
                                    ),
                            ),
                            times = 2,
                        ),
                        Step(Behaviour(error = InjectedError(529), fault = Fault(FaultKind.STREAM_ERROR, 3))),
                        Step(
                            Behaviour(
                                latency = Latency(800, 5, 1),
                                content = ContentSpec(ContentMode.FIXED, "hi", 4),
                                usage = UsageSpec(true, 1, 2, 3, 4),
                            ),
                        ),
                    ),
                otherwise = Behaviour(fault = Fault(FaultKind.HANG, 0)),
            ),
            scenario,
        )
    }

    @Test
    fun `an empty object is a scenario with no steps and no otherwise`() {
        assertEquals(Scenario(emptyList(), null), json.parse(tree("{}")))
    }

    @Test
    fun `a scenario round-trips through its JSON form`() {
        val text =
            """{"steps":[{"times":1,"latency":{"ttftMs":5}},{"times":3,"error":{"status":503,"headers":{}}}],""" +
                """"otherwise":{"content":{"mode":"echo"}}}"""
        val scenario = json.parse(tree(text))

        assertEquals(scenario, json.parse(json.write(scenario)))
        assertEquals(text, mapper.writeValueAsString(json.write(scenario)))
    }

    @Test
    fun `the largest allowed times and step count are accepted`() {
        val steps = List(1000) { """{"times":1000000}""" }.joinToString(",")

        assertEquals(1000, json.parse(tree("""{"steps":[$steps]}""")).steps.size)
    }

    @Test
    fun `more than 1000 steps is a 400 naming steps`() {
        val steps = List(1001) { "{}" }.joinToString(",")

        val rejection = assertThrows<MockRejection> { json.parse(tree("""{"steps":[$steps]}""")) }

        assertEquals("steps", rejection.param)
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            """[]|scenario""",
            """{"step":[]}|step""",
            """{"steps":{}}|steps""",
            """{"steps":[{"latency":{"ttftMs":-1}}]}|steps[0].latency.ttftMs""",
            """{"steps":[{"latency":{"perTokenMs":300001}}]}|steps[0].latency.perTokenMs""",
            """{"steps":[{"latency":{"ttft":1}}]}|steps[0].latency.ttft""",
            """{"steps":[{"error":{"status":399}}]}|steps[0].error.status""",
            """{"steps":[{"error":{"status":600}}]}|steps[0].error.status""",
            """{"steps":[{"error":{}}]}|steps[0].error.status""",
            """{"steps":[{"error":{"status":500,"headers":{"a":1}}}]}|steps[0].error.headers.a""",
            """{"steps":[{"times":0,"error":{"status":500}}]}|steps[0].times""",
            """{"steps":[{"times":1000001}]}|steps[0].times""",
            """{"steps":[{"times":2147483647}]}|steps[0].times""",
            """{"steps":[{"error":{"status":429,"headers":{"bad name":"x"}}}]}|steps[0].error.headers.bad name""",
            """{"steps":[{"error":{"status":429,"headers":{"x-a":"v\r\nx-b: injected"}}}]}|""" +
                """steps[0].error.headers.x-a""",
            """{"steps":[{"error":{"status":429,"headers":{"x-a":"v\u0000"}}}]}|steps[0].error.headers.x-a""",
            """{"steps":[{"error":{"status":429,"headers":{"":"v"}}}]}|steps[0].error.headers.""",
            """{"steps":[{"fault":{"kind":"explode"}}]}|steps[0].fault.kind""",
            """{"steps":[{"fault":{"kind":"stall","afterPieces":-1}}]}|steps[0].fault.afterPieces""",
            """{"steps":[{"fault":{"kind":"hang"},"error":{"status":500}}]}|steps[0].fault""",
            """{"steps":[{"content":{"mode":"fixed"}}]}|steps[0].content.text""",
            """{"steps":[{"content":{"mode":"poem"}}]}|steps[0].content.mode""",
            """{"steps":[{"content":{"completionTokens":4097}}]}|steps[0].content.completionTokens""",
            """{"steps":[{"usage":{"omit":"yes"}}]}|steps[0].usage.omit""",
            """{"steps":[{"usage":{"cachedTokens":-1}}]}|steps[0].usage.cachedTokens""",
            """{"otherwise":{"times":2}}|otherwise.times""",
            """{"otherwise":{"bogus":true}}|otherwise.bogus""",
        ],
    )
    fun `every rejection is a 400 naming the member`(
        body: String,
        param: String,
    ) {
        val rejection = assertThrows<MockRejection> { json.parse(tree(body)) }

        assertEquals(400, rejection.status)
        assertEquals(param, rejection.param)
    }

    private fun tree(text: String) = mapper.readTree(text.trimIndent())
}
