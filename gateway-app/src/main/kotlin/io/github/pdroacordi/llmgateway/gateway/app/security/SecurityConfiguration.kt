package io.github.pdroacordi.llmgateway.gateway.app.security

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties::class)
class SecurityConfiguration {
    // A bean factory method, so a refused address stops the context before the server binds.
    @Bean
    fun loopbackHostWebFilter(
        properties: SecurityProperties,
        environment: Environment,
    ): LoopbackHostWebFilter {
        ExposureGuard.check(environment.getProperty(SERVER_ADDRESS), properties.allowUnauthenticatedExposure)
        return LoopbackHostWebFilter(properties.allowUnauthenticatedExposure)
    }

    private companion object {
        const val SERVER_ADDRESS = "server.address"
    }
}
