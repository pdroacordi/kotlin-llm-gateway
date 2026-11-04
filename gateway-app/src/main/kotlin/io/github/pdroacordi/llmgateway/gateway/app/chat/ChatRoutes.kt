package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.server.MethodNotAllowedException

/** A functional router answers a wrong method with 404, so the path ends with a 405 catch-all. */
object ChatRoutes {
    private const val COMPLETIONS = "/v1/chat/completions"

    fun routes(handler: ChatHandler): RouterFunction<ServerResponse> =
        coRouter {
            POST(COMPLETIONS, handler::complete)
            path(COMPLETIONS) { request -> throw MethodNotAllowedException(request.method(), setOf(HttpMethod.POST)) }
        }
}
