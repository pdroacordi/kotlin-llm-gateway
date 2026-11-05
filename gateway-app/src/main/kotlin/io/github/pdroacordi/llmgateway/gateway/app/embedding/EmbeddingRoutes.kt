package io.github.pdroacordi.llmgateway.gateway.app.embedding

import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.server.MethodNotAllowedException

/** A functional router answers a wrong method with 404, so the path ends with a 405 catch-all. */
object EmbeddingRoutes {
    internal const val EMBEDDINGS = "/v1/embeddings"

    fun routes(handler: EmbeddingHandler): RouterFunction<ServerResponse> =
        coRouter {
            POST(EMBEDDINGS, handler::embed)
            path(EMBEDDINGS) { request -> throw MethodNotAllowedException(request.method(), setOf(HttpMethod.POST)) }
        }
}
