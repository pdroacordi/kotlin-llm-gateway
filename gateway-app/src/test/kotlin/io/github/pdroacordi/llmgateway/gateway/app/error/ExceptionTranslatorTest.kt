package io.github.pdroacordi.llmgateway.gateway.app.error

import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.codec.DecodingException
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ContentTooLargeException
import org.springframework.web.server.MethodNotAllowedException
import org.springframework.web.server.NotAcceptableStatusException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebInputException
import org.springframework.web.server.UnsupportedMediaTypeStatusException

class ExceptionTranslatorTest {
    @Test
    fun `a GatewayErrorException gives its own error`() {
        val error = GatewayError.InvalidRequest("Probe message.", "probe_code", "probe_param")

        assertEquals(error, GatewayErrorException(error).translate())
    }

    @Test
    fun `a 404 gives UnknownRoute`() {
        assertEquals(GatewayError.UnknownRoute, ResponseStatusException(HttpStatus.NOT_FOUND).translate())
    }

    @Test
    fun `a 405 gives MethodNotAllowed with the supported methods`() {
        assertEquals(
            GatewayError.MethodNotAllowed("GET", setOf("POST")),
            MethodNotAllowedException("GET", setOf(HttpMethod.POST)).translate(),
        )
    }

    @Test
    fun `a 406 gives NotAcceptable`() {
        assertEquals(
            GatewayError.NotAcceptable,
            NotAcceptableStatusException(listOf(MediaType.APPLICATION_JSON)).translate(),
        )
    }

    @Test
    fun `a 415 gives UnsupportedMediaType`() {
        assertEquals(
            GatewayError.UnsupportedMediaType,
            UnsupportedMediaTypeStatusException(MediaType.TEXT_PLAIN, listOf(MediaType.APPLICATION_JSON)).translate(),
        )
    }

    @Test
    fun `a 413 gives ContentTooLarge`() {
        assertEquals(
            GatewayError.ContentTooLarge,
            ContentTooLargeException(DataBufferLimitException("limit")).translate(),
        )
    }

    @Test
    fun `a request-body read error caused by a DataBufferLimitException gives ContentTooLarge`() {
        val decoding = DecodingException("token too long", DataBufferLimitException("limit"))

        assertEquals(
            GatewayError.ContentTooLarge,
            ServerWebInputException("Failed to read HTTP message", null, decoding).translate(),
        )
    }

    @Test
    fun `a DataBufferLimitException outside the request-body path is a bug, not a 413`() {
        assertEquals(
            GatewayError.Internal,
            IllegalStateException("adapter", DataBufferLimitException("upstream response too large")).translate(),
        )
        assertEquals(GatewayError.Internal, DataBufferLimitException("limit").translate())
    }

    @Test
    fun `a ServerWebInputException caused by a DecodingException gives invalid_body`() {
        assertEquals(
            GatewayError.InvalidRequest.unreadableBody,
            ServerWebInputException("unreadable", null, DecodingException("bad JSON")).translate(),
        )
    }

    @Test
    fun `a ServerWebInputException without a DecodingException gives invalid_request`() {
        assertEquals(
            GatewayError.InvalidRequest.missingOrMistypedValue,
            ServerWebInputException("missing value").translate(),
        )
    }

    @Test
    fun `any other framework 4xx gives RejectedRequest with its status`() {
        assertEquals(
            GatewayError.RejectedRequest(409),
            ResponseStatusException(HttpStatus.CONFLICT).translate(),
        )
    }

    @Test
    fun `a framework 5xx gives Internal, because only gateway variants produce 502 to 504`() {
        assertEquals(GatewayError.Internal, ResponseStatusException(HttpStatus.BAD_GATEWAY).translate())
    }

    @Test
    fun `an unknown exception gives Internal`() {
        assertEquals(GatewayError.Internal, IllegalStateException("probe-secret").translate())
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)

        assertEquals(GatewayError.Internal, first.translate())
    }

    private fun Throwable.translate(): GatewayError = toGatewayError("GET")
}
