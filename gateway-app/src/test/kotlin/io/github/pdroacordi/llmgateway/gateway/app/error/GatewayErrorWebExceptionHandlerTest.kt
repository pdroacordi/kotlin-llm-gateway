package io.github.pdroacordi.llmgateway.gateway.app.error

import com.jayway.jsonpath.JsonPath
import io.github.pdroacordi.llmgateway.gateway.app.request.GatewayHeaders
import io.github.pdroacordi.llmgateway.gateway.app.request.RequestContext
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestIdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
    private var mark = 0

    // Skips the class-level capture: docs/knowledge/gotchas/output-capture-all-includes-the-class-level-capture.md
    @BeforeEach
    fun markLog(output: CapturedOutput) {
        mark = output.length
    }

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

    // Stackless and without suppression, so no Reactor checkpoint can ride on it into Reactor Netty's log line.
    @Test
    fun `a committed response is not rewritten and a stackless abort naming the request replaces the error`() {
        val exchange = exchange()
        val context = RequestContext(ids.next(), null)
        exchange.attributes[RequestContext.ATTRIBUTE] = context
        exchange.response.setComplete().block()

        val thrown =
            assertThrows<CommittedResponseAbort> {
                handler().handle(exchange, IllegalStateException("mid-stream")).block()
            }

        assertTrue(context.requestId.toString() in thrown.message.orEmpty()) { thrown.message.orEmpty() }
        assertFalse("mid-stream" in thrown.toString()) { thrown.toString() }
        assertEquals(0, thrown.stackTrace.size)
        thrown.addSuppressed(IllegalStateException("checkpoint"))
        assertEquals(0, thrown.suppressed.size)
    }

    @Test
    fun `an error quoting a disconnect after commit is logged at ERROR and aborts`(output: CapturedOutput) {
        val exchange = exchange()
        exchange.response.setComplete().block()

        assertThrows<CommittedResponseAbort> { handler().handle(exchange, disconnectPhrase()).block() }

        assertEquals(1, errorLines(output).size) { output.all }
    }

    @Test
    fun `a write failure after commit aborts instead of passing the failure on`() {
        val exchange = exchange()
        exchange.response.setWriteHandler { Mono.error(IllegalStateException("codec")) }

        assertThrows<CommittedResponseAbort> {
            handler().handle(exchange, ResponseStatusException(HttpStatus.NOT_FOUND)).block()
        }
    }

    // A successful write proves the client was there, whatever the exception's cause quotes.
    @Test
    fun `a 5xx quoting a disconnect gets the envelope and one ERROR line`(output: CapturedOutput) {
        val exchange = exchange()

        handler().handle(exchange, disconnectPhrase()).block()

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.response.statusCode)
        assertEquals("internal_error", JsonPath.parse(exchange.body()).read<String>("$.error.code"))
        assertEquals(1, errorLines(output).size) { output.all }
    }

    @Test
    fun `a 5xx whose write fails because the client is gone logs no ERROR line`(output: CapturedOutput) {
        val exchange = exchange()
        exchange.response.setWriteHandler { Mono.error(IOException("Broken pipe")) }

        handler().handle(exchange, IllegalStateException("bug")).block()

        assertTrue(errorLines(output).isEmpty()) { output.all }
    }

    @Test
    fun `a 5xx whose write fails for another reason logs the error and the constant body is written`(
        output: CapturedOutput,
    ) {
        val codecs = ServerCodecConfigurer.create().apply { registerDefaults(false) }
        val exchange = exchange()

        handler(codecs).handle(exchange, IllegalStateException("bug")).block()

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.response.statusCode)
        assertEquals(2, errorLines(output).size) { output.all }
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

        assertTrue(errorLines(output).isEmpty()) { output.all }
    }

    // Log lines start at column 0; stack frames are indented.
    private fun errorLines(output: CapturedOutput): List<String> =
        output.all
            .substring(mark)
            .lines()
            .filter { it.trimStart() == it && " ERROR " in it }

    private fun disconnectPhrase() = IllegalStateException("adapter", IOException("Connection reset by peer"))

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
