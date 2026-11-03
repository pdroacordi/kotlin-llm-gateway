package io.github.pdroacordi.llmgateway.mockprovider.error

import io.github.pdroacordi.llmgateway.mockprovider.web.ErrorBodies
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// In `.error` because ARCH-10 confines every reference to the handler types to this package.
@Configuration(proxyBeanMethods = false)
class ErrorHandlingConfiguration {
    @Bean
    fun mockErrorWebExceptionHandler(bodies: ErrorBodies): MockErrorWebExceptionHandler =
        MockErrorWebExceptionHandler(bodies)
}
