package io.github.pdroacordi.llmgateway.gateway.app.error

import org.springframework.boot.webflux.error.ErrorWebExceptionHandler
import org.springframework.web.server.*
import reactor.core.publisher.Mono

// The one place error handling may live: <app root>.error.
class AllowedHandler : ErrorWebExceptionHandler {
    override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> = Mono.error(ex)
}
