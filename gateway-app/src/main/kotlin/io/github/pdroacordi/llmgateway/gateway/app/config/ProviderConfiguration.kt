package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelResolver
import io.github.pdroacordi.llmgateway.gateway.core.chat.PassThroughModelResolver
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiChatAdapter
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** The only `main` code that names `.gateway.providers.openai` (ARCH-12). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiProperties::class)
class ProviderConfiguration {
    @Bean
    fun chatModelPort(
        properties: OpenAiProperties,
        mapper: JsonMapper,
        clock: Clock,
    ): ChatModelPort = OpenAiChatAdapter(properties.toAdapterConfig(), mapper, clock).also { it.warmUp() }

    @Bean
    fun modelResolver(): ModelResolver = PassThroughModelResolver(DeploymentId(OPENAI), ProviderId(OPENAI))

    private companion object {
        const val OPENAI = "openai"
    }
}
