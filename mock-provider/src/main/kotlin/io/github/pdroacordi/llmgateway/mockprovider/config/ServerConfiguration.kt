package io.github.pdroacordi.llmgateway.mockprovider.config

import org.slf4j.LoggerFactory
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/** The server's lifecycle: how fast it stops, and the one INFO line when it starts. */
@Configuration(proxyBeanMethods = false)
class ServerConfiguration {
    // Bounds both the startup bind and, on stop, the wait for open connections (a held `hang` request keeps one open
    // until this runs out; ShutdownIntegrationTest). 3 s is also Reactor Netty's own dispose default; set explicitly
    // because the bind needs headroom on a loaded runner.
    @Bean
    fun nettyLifecycleTimeout(): WebServerFactoryCustomizer<NettyReactiveWebServerFactory> =
        WebServerFactoryCustomizer { factory -> factory.setLifecycleTimeout(Duration.ofSeconds(LIFECYCLE_SECONDS)) }

    /** Where the mock listens and with which defaults. */
    @Bean
    fun startupLog(properties: MockProperties): ApplicationListener<WebServerInitializedEvent> =
        ApplicationListener { event ->
            LoggerFactory.getLogger(ServerConfiguration::class.java).info(
                "mock-provider on port {}; models {}; defaults {}; max hold {}",
                event.webServer.port,
                properties.models,
                properties.defaults,
                properties.maxHold,
            )
        }

    private companion object {
        const val LIFECYCLE_SECONDS = 3L
    }
}
