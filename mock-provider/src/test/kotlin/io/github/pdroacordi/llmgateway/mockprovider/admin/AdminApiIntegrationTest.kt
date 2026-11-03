package io.github.pdroacordi.llmgateway.mockprovider.admin

import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.bodyText
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chat
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chatBody
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.getJson
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.json
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.putScenario
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.reset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AdminApiIntegrationTest(
    @Autowired private val client: WebTestClient,
) {
    @BeforeEach
    fun resetMock() = client.reset()

    @Test
    fun `put, list with stepsConsumed, delete one, delete all`() {
        client.putScenario(
            "a",
            """{"steps":[{"times":2,"error":{"status":503}}],"otherwise":{"latency":{"ttftMs":1}}}""",
        )
        client.putScenario("*", "{}")
        client.chat(chatBody("x", model = "a"))

        client
            .get()
            .uri("/__mock/scenarios")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json(
                """{"data":[
                {"model":"*","steps":[],"otherwise":null,"stepsConsumed":0},
                {"model":"a","steps":[{"times":2,"error":{"status":503,"headers":{}}}],"otherwise":{"latency":{"ttftMs":1}},
                 "stepsConsumed":1}]}""",
                JsonCompareMode.STRICT,
            )

        client
            .delete()
            .uri("/__mock/scenarios/{model}", "a")
            .exchange()
            .expectStatus()
            .isNoContent
        client
            .delete()
            .uri("/__mock/scenarios/{model}", "absent")
            .exchange()
            .expectStatus()
            .isNoContent
        assertEquals(listOf("*"), client.getJson("/__mock/scenarios").json<List<String>>("$.data[*].model"))
        client
            .delete()
            .uri("/__mock/scenarios")
            .exchange()
            .expectStatus()
            .isNoContent
        assertEquals(emptyList<String>(), client.getJson("/__mock/scenarios").json<List<String>>("$.data"))
    }

    @Test
    fun `a put replaces the scenario and restarts its cursor`() {
        val scenario = """{"steps":[{"error":{"status":503}}]}"""
        client.putScenario("m", scenario)
        client.chat(chatBody("x", model = "m"))
        client.putScenario("m", scenario)

        assertEquals(503, client.chat(chatBody("x", model = "m")).status.value())
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            """{"steps":[{"bogus":1}]}|steps[0].bogus""",
            """{"steps":[{"latency":{"ttftMs":-5}}]}|steps[0].latency.ttftMs""",
            """{"steps":[{"error":{"status":600}}]}|steps[0].error.status""",
            """{"steps":[{"error":{"status":399}}]}|steps[0].error.status""",
            """{"steps":[{"times":0,"error":{"status":500}}]}|steps[0].times""",
            """{"otherwise":{"fault":{"kind":"explode"}}}|otherwise.fault.kind""",
        ],
    )
    fun `invalid scenarios are 400 naming the member and leave the stored scenarios unchanged`(
        scenario: String,
        param: String,
    ) {
        client.putScenario("kept", """{"otherwise":{"latency":{"ttftMs":1}}}""")
        val before = client.getJson("/__mock/scenarios")

        val result =
            client
                .put()
                .uri("/__mock/scenarios/{model}", "kept")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(scenario)
                .exchange()
                .expectBody()
                .returnResult()

        assertEquals(400, result.status.value())
        assertEquals("invalid_request_error", result.json<String>("$.error.type"))
        assertEquals(param, result.json<String>("$.error.param"))
        assertEquals(setOf("message", "type", "param", "code"), result.json<Map<String, Any>>("$.error").keys)
        assertEquals(before, client.getJson("/__mock/scenarios"))
    }

    @Test
    fun `a scenario body that is not JSON is 400 in the OpenAI envelope`() {
        val result =
            client
                .put()
                .uri("/__mock/scenarios/{model}", "m")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"steps":""")
                .exchange()
                .expectBody()
                .returnResult()

        assertEquals(400, result.status.value())
        assertEquals("invalid_request_error", result.json<String>("$.error.type"))
    }

    @ParameterizedTest
    @CsvSource("limit=0, limit", "limit=1001, limit", "since=x, since", "header=novalue, header")
    fun `invalid journal queries are 400 naming the parameter`(
        query: String,
        param: String,
    ) {
        val result =
            client
                .get()
                .uri("/__mock/requests?$query")
                .exchange()
                .expectBody()
                .returnResult()

        assertEquals(400, result.status.value()) { result.bodyText() }
        assertEquals(param, result.json<String>("$.error.param"))
    }

    @Test
    fun `stats has the documented members`() {
        client.chat(chatBody("x"))

        val stats = client.getJson("/__mock/stats")
        assertEquals(setOf("lastSeq", "inFlight", "counts", "journal"), stats.json<Map<String, Any>>("$").keys)
        assertEquals(mapOf("completed" to 1), stats.json<Map<String, Int>>("$.counts[0].outcomes"))
        assertEquals(mapOf("capacity" to 1000, "size" to 1, "dropped" to 0), stats.json<Map<String, Int>>("$.journal"))
    }
}
