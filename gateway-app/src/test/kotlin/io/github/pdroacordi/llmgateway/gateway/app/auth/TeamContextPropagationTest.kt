package io.github.pdroacordi.llmgateway.gateway.app.auth

import io.github.pdroacordi.llmgateway.gateway.app.request.GatewayHeaders
import io.github.pdroacordi.llmgateway.gateway.app.request.RequestContext
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** The runtime proof that the two `CoWebFilter`s nest, through both kinds of handler (GW-04 section 5). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TeamContextPropagationTest.Probes::class)
@ExtendWith(OutputCaptureExtension::class)
class TeamContextPropagationTest(
    @LocalServerPort port: Int,
) {
    data class Seen(
        val requestId: String?,
        val team: String?,
        val mdcRequestId: String?,
        val mdcTeam: String?,
    )

    data class Probe(
        val before: Seen,
        val after: Seen,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class Probes {
        @Bean
        fun probeRoutes(): RouterFunction<ServerResponse> =
            coRouter {
                GET("/v1/test-probe/router") { ServerResponse.ok().bodyValueAndAwait(probe("router")) }
                GET("/admin/test-probe") { ServerResponse.ok().bodyValueAndAwait(probe("admin")) }
                GET("/test-probe") { ServerResponse.ok().bodyValueAndAwait(probe("open")) }
            }

        @Bean
        fun probeController(): ProbeEndpoint = ProbeEndpoint()
    }

    @RestController
    class ProbeEndpoint {
        @GetMapping("/v1/test-probe/controller")
        suspend fun controller(): Probe = probe("controller")
    }

    private val client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:$port").build()

    private fun get(
        path: String,
        authorization: String = TestKeys.BEARER,
    ): Pair<JsonNode, String> {
        val result =
            client
                .get()
                .uri(path)
                .header("Authorization", authorization)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .returnResult()
        val requestId = checkNotNull(result.responseHeaders.getFirst(GatewayHeaders.REQUEST_ID))
        return JsonMapper.builder().build().readTree(checkNotNull(result.responseBody)) to requestId
    }

    @ParameterizedTest
    @ValueSource(strings = ["router", "controller"])
    fun `the context and MDC reach the handler before and after a dispatcher hop`(
        kind: String,
        output: CapturedOutput,
    ) {
        val (body, requestId) = get("/v1/test-probe/$kind")

        listOf("before", "after").forEach { moment ->
            val seen = body.get(moment)
            assertEquals(requestId, seen.get("requestId").stringValue(), moment)
            assertEquals(TestKeys.TEAM_ID.toString(), seen.get("team").stringValue(), moment)
            assertEquals(requestId, seen.get("mdcRequestId").stringValue(), moment)
            assertEquals(TestKeys.TEAM_ID.toString(), seen.get("mdcTeam").stringValue(), moment)
        }
        val line = output.all.lines().single { "$LOG_LINE $kind" in it && requestId in it }
        assertTrue("[$requestId] [${TestKeys.TEAM_ID}]" in line) { line }
    }

    // On the open path a valid team key is sent too: outside the scope it must not be resolved.
    @ParameterizedTest
    @CsvSource("/admin/test-probe, admin", "/test-probe, team")
    fun `no team is in the context of a request outside v1`(
        path: String,
        credential: String,
    ) {
        val (body, requestId) = get(path, if (credential == "admin") TestKeys.ADMIN_BEARER else TestKeys.BEARER)

        listOf("before", "after").forEach { moment ->
            val seen = body.get(moment)
            assertEquals(requestId, seen.get("requestId").stringValue(), moment)
            assertTrue(seen.absent("team")) { "$moment: $seen" }
            assertTrue(seen.absent("mdcTeam")) { "$moment: $seen" }
        }
    }

    private fun JsonNode.absent(member: String): Boolean = get(member).let { it == null || it.isNull }

    private companion object {
        const val LOG_LINE = "team context probe"
        val log: Logger = LoggerFactory.getLogger(TeamContextPropagationTest::class.java)

        suspend fun seen(): Seen {
            val context = currentCoroutineContext()
            return Seen(
                context[RequestContext]?.requestId?.toString(),
                context[TeamContext]?.team?.toString(),
                MDC.get(GatewayHeaders.MDC_REQUEST_ID),
                MDC.get(GatewayHeaders.MDC_TEAM_ID),
            )
        }

        // The hop to another pool is the point of the probe, so the dispatcher is not injected.
        @Suppress("InjectDispatcher")
        suspend fun probe(kind: String): Probe {
            val before = seen()
            val after =
                withContext(Dispatchers.Default) {
                    log.info("$LOG_LINE $kind")
                    seen()
                }
            return Probe(before, after)
        }
    }
}
