package io.github.pdroacordi.llmgateway.mockprovider.error

import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.KEY
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.bodyText
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chatBody
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.json
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.messages
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.reset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.reactive.handler.WebFluxResponseStatusExceptionHandler
import org.springframework.web.server.WebExceptionHandler

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(MockErrorEnvelopeIntegrationTest.FailingRoute::class)
@ExtendWith(OutputCaptureExtension::class)
class MockErrorEnvelopeIntegrationTest(
    @Autowired private val client: WebTestClient,
    @Autowired private val context: ApplicationContext,
) {
    /** A test-only route that fails like a bug in the mock would. */
    @TestConfiguration(proxyBeanMethods = false)
    class FailingRoute {
        @Bean
        fun boomRoute(): RouterFunction<ServerResponse> = coRouter { GET("/v1/test-boom") { error(BUG_MESSAGE) } }
    }

    @BeforeEach
    fun resetMock() = client.reset()

    @Test
    fun `the context holds exactly one ErrorWebExceptionHandler, the mock's, and no advice`() {
        val handlers = context.getBeansOfType(ErrorWebExceptionHandler::class.java).values
        val all = context.getBeansOfType(WebExceptionHandler::class.java).values.map { it::class.java }

        assertEquals(1, handlers.size) { handlers.toString() }
        assertTrue(handlers.single() is MockErrorWebExceptionHandler)
        assertEquals(
            setOf(MockErrorWebExceptionHandler::class.java, WebFluxResponseStatusExceptionHandler::class.java),
            all.toSet(),
        )
        assertTrue(context.getBeanNamesForAnnotation(ControllerAdvice::class.java).isEmpty())
    }

    @Test
    fun `an unknown path is 404 in the OpenAI shape`() {
        val result = exchange { post().uri("/v1/x").contentType(MediaType.APPLICATION_JSON).bodyValue("{}") }

        assertEquals(404, result.status.value())
        assertEquals(MediaType.APPLICATION_JSON, result.responseHeaders.contentType)
        assertEquals(
            """{"error":{"message":"Unknown request URL: POST /v1/x.","type":"invalid_request_error",""" +
                """"param":null,"code":null}}""",
            result.bodyText(),
        )
    }

    @Test
    fun `an unknown path under v1 messages is 404 in the Anthropic shape`() {
        val result =
            exchange { post().uri("/v1/messages/count_tokens").contentType(MediaType.APPLICATION_JSON).bodyValue("{}") }

        assertEquals(404, result.status.value())
        assertEquals("not_found_error", result.json<String>("$.error.type"))
        assertEquals(result.responseHeaders.getFirst("request-id"), result.json<String>("$.request_id"))
    }

    @Test
    fun `malformed JSON on v1 messages is 400 invalid_request_error in the Anthropic shape, without echoing it`() {
        val result = client.messages("""{"a":""")

        assertEquals(400, result.status.value())
        assertEquals("error", result.json<String>("$.type"))
        assertEquals("invalid_request_error", result.json<String>("$.error.type"))
        assertFalse("""{"a":""" in result.bodyText())
        assertEquals(setOf("type", "error", "request_id"), result.json<Map<String, Any>>("$").keys)
    }

    @Test
    fun `a wrong method is 405 with an allow header`() {
        val result = exchange { get().uri("/v1/chat/completions").header("authorization", "Bearer $KEY") }

        assertEquals(405, result.status.value())
        assertEquals("POST", result.responseHeaders.getFirst("allow"))
        assertEquals("invalid_request_error", result.json<String>("$.error.type"))
    }

    @Test
    fun `an Accept the route cannot meet is 406`() {
        val result =
            exchange {
                post()
                    .uri("/v1/chat/completions")
                    .header("authorization", "Bearer $KEY")
                    .accept(MediaType.TEXT_PLAIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(chatBody("x"))
            }

        assertEquals(406, result.status.value())
        assertEquals(setOf("message", "type", "param", "code"), result.json<Map<String, Any>>("$.error").keys)
    }

    @Test
    fun `a non-JSON Content-Type is 415`() {
        val result =
            exchange {
                post()
                    .uri("/v1/chat/completions")
                    .header("authorization", "Bearer $KEY")
                    .contentType(MediaType.TEXT_PLAIN)
                    .bodyValue(chatBody("x"))
            }

        assertEquals(415, result.status.value())
        assertEquals("invalid_request_error", result.json<String>("$.error.type"))
    }

    @Test
    fun `a bug is 500 with no exception text in the body, logged once without the query string`(
        output: CapturedOutput,
    ) {
        val result = exchange { get().uri("/v1/test-boom?$QUERY") }

        assertEquals(500, result.status.value())
        assertEquals(
            """{"error":{"message":"mock-provider failed while handling the request.","type":"server_error",""" +
                """"param":null,"code":null}}""",
            result.bodyText(),
        )
        assertFalse(BUG_MESSAGE in result.bodyText())
        assertTrue(BUG_MESSAGE in output.all) { "the stack trace is logged" }
        assertFalse(QUERY in output.all) { output.all }
        assertEquals(1, output.all.lines().count { it.trimStart() == it && " ERROR " in it }) { output.all }
    }

    private fun exchange(
        request: WebTestClient.() -> WebTestClient.RequestHeadersSpec<*>,
    ): EntityExchangeResult<ByteArray> =
        client
            .request()
            .exchange()
            .expectBody()
            .returnResult()

    private companion object {
        const val BUG_MESSAGE = "probe failure with internal detail"
        const val QUERY = "secret-query=value-from-the-client"
    }
}
