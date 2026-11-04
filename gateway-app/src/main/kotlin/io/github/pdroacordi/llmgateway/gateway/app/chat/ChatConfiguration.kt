package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCompletionService
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelResolver
import io.github.pdroacordi.llmgateway.gateway.core.chat.UsageEstimator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ChatConfiguration {
    @Bean
    fun usageEstimator(): UsageEstimator = UsageEstimator()

    @Bean
    fun chatCompletionService(
        resolver: ModelResolver,
        port: ChatModelPort,
        estimator: UsageEstimator,
    ): ChatCompletionService = ChatCompletionService(resolver, port, estimator)

    @Bean
    fun chatHandler(
        service: ChatCompletionService,
        mapper: JsonMapper,
        clock: Clock,
    ): ChatHandler = ChatHandler(service, mapper, clock)

    @Bean
    fun chatRoutes(handler: ChatHandler): RouterFunction<ServerResponse> = ChatRoutes.routes(handler)
}
