package io.github.pdroacordi.llmgateway.mockprovider.web

import io.github.pdroacordi.llmgateway.mockprovider.admin.AdminHandlers
import io.github.pdroacordi.llmgateway.mockprovider.anthropic.AnthropicHandler
import io.github.pdroacordi.llmgateway.mockprovider.openai.OpenAiHandlers
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.server.CoRouterFunctionDsl
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.server.MethodNotAllowedException

/**
 * Provider paths unchanged, so the base URL is the only difference from the real provider (FND-03 section 4). A
 * functional router answers a wrong method with 404, so each provider path ends with a 405 catch-all.
 */
object MockRoutes {
    fun routes(
        openAi: OpenAiHandlers,
        anthropic: AnthropicHandler,
        admin: AdminHandlers,
    ): RouterFunction<ServerResponse> =
        coRouter {
            POST("/v1/chat/completions", openAi::chat)
            POST("/v1/embeddings", openAi::embeddings)
            GET("/v1/models", openAi::models)
            POST("/v1/messages", anthropic::messages)
            notAllowed("/v1/chat/completions", HttpMethod.POST)
            notAllowed("/v1/embeddings", HttpMethod.POST)
            notAllowed("/v1/models", HttpMethod.GET)
            notAllowed("/v1/messages", HttpMethod.POST)

            "/__mock".nest {
                PUT("/scenarios/{model}", admin::putScenario)
                GET("/scenarios") { admin.listScenarios() }
                DELETE("/scenarios/{model}", admin::deleteScenario)
                DELETE("/scenarios") { admin.deleteScenarios() }
                GET("/requests", admin::requests)
                GET("/stats") { admin.stats() }
                POST("/reset") { admin.reset() }
            }
        }

    private fun CoRouterFunctionDsl.notAllowed(
        path: String,
        allowed: HttpMethod,
    ) {
        path(path) { request -> throw MethodNotAllowedException(request.method(), setOf(allowed)) }
    }
}
