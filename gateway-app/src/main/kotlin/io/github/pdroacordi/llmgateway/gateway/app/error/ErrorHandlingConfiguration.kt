package io.github.pdroacordi.llmgateway.gateway.app.error

import io.github.pdroacordi.llmgateway.gateway.core.request.RequestIdGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.codec.ServerCodecConfigurer

// In `.error` because ARCH-10 confines every reference to the handler types to this package.
@Configuration(proxyBeanMethods = false)
class ErrorHandlingConfiguration {
    @Bean
    fun gatewayErrorWebExceptionHandler(
        codecs: ServerCodecConfigurer,
        ids: RequestIdGenerator,
    ): GatewayErrorWebExceptionHandler = GatewayErrorWebExceptionHandler(codecs, ids)
}
