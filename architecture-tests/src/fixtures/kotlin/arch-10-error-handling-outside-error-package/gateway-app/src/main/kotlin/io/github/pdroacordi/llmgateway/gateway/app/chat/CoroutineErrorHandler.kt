package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.springframework.web.server.CoWebExceptionHandler
import org.springframework.web.server.ServerWebExchange

class CoroutineErrorHandler : CoWebExceptionHandler() {
    override suspend fun coHandle(exchange: ServerWebExchange, ex: Throwable) = throw ex
}
