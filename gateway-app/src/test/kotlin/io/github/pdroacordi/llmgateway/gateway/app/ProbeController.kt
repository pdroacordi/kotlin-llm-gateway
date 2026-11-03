package io.github.pdroacordi.llmgateway.gateway.app

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.app.request.GatewayHeaders
import io.github.pdroacordi.llmgateway.gateway.app.request.RequestContext
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.test.context.TestComponent
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Test-only endpoints that let FND-04 prove its error and context behaviour without product endpoints
 * (ADR-FND-04-006). `@TestComponent` keeps it out of component scanning; tests bring it in with `@Import`.
 */
@TestComponent
@RestController
@RequestMapping("/test-probe")
class ProbeController {
    @PostMapping("/echo", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun echo(
        @RequestBody body: Map<String, Any?>,
    ): Map<String, Any?> = body

    @GetMapping("/json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun json(): Map<String, String> = mapOf("probe" to "ok")

    @GetMapping("/boom")
    fun boom(): Nothing = error(SECRET)

    @GetMapping("/gateway-error")
    fun gatewayError(): Nothing =
        throw GatewayErrorException(GatewayError.InvalidRequest("Probe message.", "probe_code", "probe_param"))

    // Commits the response, then fails: the mid-stream path GW-02 turns into an SSE error event.
    @GetMapping("/committed")
    fun committed(exchange: ServerWebExchange): Mono<Void> {
        val response = exchange.response
        return response
            .writeWith(Mono.fromSupplier { response.bufferFactory().wrap("partial".encodeToByteArray()) })
            .then(Mono.error(IllegalStateException("mid-stream")))
    }

    // The hop to another pool is the point of the probe, so the dispatcher is not injected.
    @Suppress("InjectDispatcher")
    @GetMapping("/context", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun context(): ContextProbe =
        withContext(Dispatchers.Default) {
            delay(1)
            log.info(CONTEXT_LOG_LINE)
            ContextProbe(
                fromCoroutine = currentCoroutineContext()[RequestContext]?.requestId?.toString(),
                fromMdc = MDC.get(GatewayHeaders.MDC_REQUEST_ID),
            )
        }

    data class ContextProbe(
        val fromCoroutine: String?,
        val fromMdc: String?,
    )

    companion object {
        const val SECRET = "probe-secret"
        const val CONTEXT_LOG_LINE = "probe context line"
        private val log = LoggerFactory.getLogger(ProbeController::class.java)
    }
}
