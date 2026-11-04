package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelPrefix
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelResolver
import io.github.pdroacordi.llmgateway.gateway.core.chat.PrefixModelResolver
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.chat.RoutingChatModelPort
import io.github.pdroacordi.llmgateway.gateway.providers.anthropic.AnthropicChatAdapter
import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiChatAdapter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Fallback
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/**
 * The only `main` code that names `.gateway.providers.openai` and `.anthropic` (ARCH-12). Adapters are concrete beans,
 * so Spring's inferred `close()` runs; `@Fallback` leaves the router as the one [ChatModelPort] to inject by type.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    OpenAiProperties::class,
    AnthropicProperties::class,
    ProviderSelectionProperties::class,
)
class ProviderConfiguration {
    @Bean
    @Fallback
    @ConditionalOnProperty("llmgw.providers.openai.enabled", havingValue = "true")
    fun openAiChatAdapter(
        properties: OpenAiProperties,
        mapper: JsonMapper,
        clock: Clock,
    ): OpenAiChatAdapter = OpenAiChatAdapter(properties.toAdapterConfig(), mapper, clock).also { it.warmUp() }

    @Bean
    @Fallback
    @ConditionalOnProperty("llmgw.providers.anthropic.enabled", havingValue = "true")
    fun anthropicChatAdapter(
        properties: AnthropicProperties,
        mapper: JsonMapper,
        clock: Clock,
    ): AnthropicChatAdapter = AnthropicChatAdapter(properties.toAdapterConfig(), mapper, clock).also { it.warmUp() }

    @Bean
    fun chatModelPort(
        openai: ObjectProvider<OpenAiChatAdapter>,
        anthropic: ObjectProvider<AnthropicChatAdapter>,
        openAiProperties: OpenAiProperties,
        anthropicProperties: AnthropicProperties,
        selection: ProviderSelectionProperties,
    ): ChatModelPort {
        val ports =
            buildMap {
                openai.ifAvailable { put(ProviderId(OPENAI), it) }
                anthropic.ifAvailable { put(ProviderId(ANTHROPIC), it) }
            }
        checkEnabled(OPENAI, openAiProperties.enabled, ports)
        checkEnabled(ANTHROPIC, anthropicProperties.enabled, ports)
        selection.validate(ports.keys.map { it.value }.toSet())
        return RoutingChatModelPort(ports)
    }

    @Bean
    fun modelResolver(
        selection: ProviderSelectionProperties,
        anthropic: AnthropicProperties,
    ): ModelResolver {
        // Only for an enabled provider: on an OpenAI-only start `claude-x` goes to OpenAI, as it always did.
        val prefixes = if (anthropic.enabled) anthropic.modelPrefixes else emptyList()
        return PrefixModelResolver(
            prefixes.map { ModelPrefix(it, ProviderId(ANTHROPIC)) },
            ProviderId(selection.defaultProvider),
        )
    }

    // The bean condition matches only `true`, the binder also `1`, `yes` and `on`: see
    // docs/knowledge/gotchas/conditional-on-property-and-the-binder-read-true-differently.md.
    private fun checkEnabled(
        provider: String,
        enabled: Boolean,
        ports: Map<ProviderId, ChatModelPort>,
    ) {
        check(enabled == (ProviderId(provider) in ports)) {
            "llmgw.providers.$provider.enabled must be written true or false: its value and the adapter disagree."
        }
    }

    private companion object {
        const val OPENAI = "openai"
        const val ANTHROPIC = "anthropic"
    }
}
