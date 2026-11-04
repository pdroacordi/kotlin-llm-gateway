package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.app.web.ClientStallHandler
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Nothing in WebFlux or Reactor Netty bounds how long a write may wait, so a client that stops reading would hold its
 * connection and its upstream slot for ever. Closing the channel runs the normal cancel path (ADR-GW-02-009).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServerStreamProperties::class)
class ServerStreamConfiguration {
    @Bean
    fun clientWriteTimeout(
        server: ServerStreamProperties,
        openai: OpenAiProperties,
        anthropic: AnthropicProperties,
    ): NettyServerCustomizer {
        server.validate(
            listOfNotNull(
                openai.stream.idleTimeout.takeIf { openai.enabled },
                anthropic.stream.idleTimeout.takeIf { anthropic.enabled },
            ).minOrNull(),
        )
        return NettyServerCustomizer { http ->
            http.doOnConnection { connection ->
                connection.addHandlerLast(HANDLER, ClientStallHandler(server.clientWriteTimeout))
            }
        }
    }

    private companion object {
        const val HANDLER = "llmgw.clientWriteTimeout"
    }
}
