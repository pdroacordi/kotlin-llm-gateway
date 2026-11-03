package io.github.pdroacordi.llmgateway.gateway.app.error

import com.jayway.jsonpath.JsonPath
import io.github.pdroacordi.llmgateway.gateway.app.request.GatewayHeaders
import io.github.pdroacordi.llmgateway.gateway.app.request.RequestContext
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestIdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.core.codec.DecodingException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Mono
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

// The paths the integration tests cannot reach: fallback body, committed response, disconnected client.
@ExtendWith(OutputCaptureExtension::class)
class GatewayErrorWebExceptionHandlerTest {
    private val ids = RequestIdGenerator(Clock.fixed(Instant.ofEpochMilli(MILLIS), ZoneOffset.UTC), Random(1))

    @Test
    fun `without the filter's attribute the handler generates a request ID itself`() {
        val exchange = exchange()

        handler().handle(exchange, ResponseStatusException(HttpStatus.NOT_FOUND)).block()

        assertEquals(HttpStatus.NOT_FOUND, exchange.response.statusCode)
        assertEquals(MediaType.APPLICATION_JSON, exchange.response.headers.contentType)
        assertTrue(exchange.requestIdHeader().startsWith("0189")) { exchange.requestIdHeader() }
        assertTrue("\"unknown_url\"" in exchange.body()) { exchange.body() }
    }

    @Test
    fun `the request ID comes from the filter's exchange attribute`() {
        val exchange = exchange()
        val context = RequestContext(ids.next(), null)
        exchange.attributes[RequestContext.ATTRIBUTE] = context

        handler().handle(exchange, IllegalStateException("probe-secret")).block()

        assertEquals(context.requestId.toString(), exchange.requestIdHeader())
        assertTrue(context.requestId.toString() in exchange.body()) { exchange.body() }
        assertFalse("probe-secret" in exchange.body()) { exchange.body() }
    }

    @Test
    fun `when no codec can write JSON a constant 500 envelope is written`() {
        val codecs = ServerCodecConfigurer.create().apply { registerDefaults(false) }
        val exchange = exchange()

        handler(codecs).handle(exchange, ResponseStatusException(HttpStatus.NOT_FOUND)).block()

        val requestId = exchange.requestIdHeader()
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.response.statusCode)
        assertEquals(MediaType.APPLICATION_JSON, exchange.response.headers.contentType)
        assertEquals(
            """{"error":{"message":"The gateway had an internal error. Include the request ID $requestId """ +
                """if you report it.","type":"server_error","param":null,"code":"internal_error"}}""",
            exchange.body(),
        )
    }

    @Test
    fun `a committed response is not rewritten and the error propagates`() {
        val exchange = exchange()
        exchange.response.setComplete().block()
        val failure = IllegalStateException("mid-stream")

        val thrown = assertThrows<IllegalStateException> { handler().handle(exchange, failure).block() }

        assertSame(failure, thrown)
    }

    // DisconnectedClientHelper matches phrases in any cause's message, so this only looks like a disconnect
    // (docs/knowledge/bugs/error-mapper-disconnect-check-turns-errors-into-empty-200.md).
    @Test
    fun `a decoding error quoting broken pipe still gets the 400 invalid_body envelope`() {
        val exchange = exchange()
        val decoding = DecodingException("JSON decoding error: Cannot deserialize value from String \"broken pipe\"")

        handler().handle(exchange, ServerWebInputException("Failed to read HTTP message", null, decoding)).block()

        assertEquals(HttpStatus.BAD_REQUEST, exchange.response.statusCode)
        assertEquals("invalid_body", JsonPath.parse(exchange.body()).read<String>("$.error.code"))
    }

    @Test
    fun `a write that fails because the client is gone completes without an ERROR log`(output: CapturedOutput) {
        val exchange = exchange()
        exchange.response.setWriteHandler { Mono.error(IOException("Broken pipe")) }

        handler().handle(exchange, ResponseStatusException(HttpStatus.NOT_FOUND)).block()

        // Log lines start at column 0; stack frames are indented.
        val errors = output.all.lines().filter { it.trimStart() == it && " ERROR " in it }
        assertTrue(errors.isEmpty()) { errors.joinToString("\n") }
    }

    private fun handler(codecs: ServerCodecConfigurer = ServerCodecConfigurer.create()) =
        GatewayErrorWebExceptionHandler(codecs, ids)

    private fun exchange(): MockServerWebExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/x"))

    private fun MockServerWebExchange.requestIdHeader(): String =
        checkNotNull(response.headers.getFirst(GatewayHeaders.REQUEST_ID))

    private fun MockServerWebExchange.body(): String = response.bodyAsString.block().orEmpty()

    private companion object {
        const val MILLIS = 1_688_208_000_000L
    }
}
