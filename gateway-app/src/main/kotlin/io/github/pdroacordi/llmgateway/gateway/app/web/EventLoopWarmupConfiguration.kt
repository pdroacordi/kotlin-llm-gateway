package io.github.pdroacordi.llmgateway.gateway.app.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping

@Configuration(proxyBeanMethods = false)
class EventLoopWarmupConfiguration {
    @Bean
    fun eventLoopWarmup(mappings: List<RequestMappingHandlerMapping>): EventLoopWarmup = EventLoopWarmup(mappings)
}
