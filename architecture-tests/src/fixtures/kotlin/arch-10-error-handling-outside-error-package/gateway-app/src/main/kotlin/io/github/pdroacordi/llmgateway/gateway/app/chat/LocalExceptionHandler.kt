package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.springframework.web.server.*
import reactor.core.publisher.Mono

class LocalExceptionHandler : WebExceptionHandler {
    override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> = Mono.error(ex)
}

class QualifiedBootHandler : org.springframework.boot.webflux.error.ErrorWebExceptionHandler {
    override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> = Mono.error(ex)
}
