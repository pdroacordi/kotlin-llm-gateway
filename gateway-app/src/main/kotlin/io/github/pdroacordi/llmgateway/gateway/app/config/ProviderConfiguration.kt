package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelPrefix
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelResolver
import io.github.pdroacordi.llmgateway.gateway.core.chat.PrefixModelResolver
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.chat.RoutingChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingCpuPort
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingModelPort
import io.github.pdroacordi.llmgateway.gateway.core.embedding.RoutingEmbeddingModelPort
import io.github.pdroacordi.llmgateway.gateway.providers.anthropic.AnthropicChatAdapter
import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiChatAdapter
import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiEmbeddingAdapter
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.CpuLane
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Fallback
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

/**
 * The only `main` code that names `.gateway.providers.openai` and `.anthropic` (ARCH-12). Adapters are concrete beans,
 * so Spring's inferred `close()` runs; `@Fallback` leaves each router as the one port to inject by type.
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
    @Fallback
    @ConditionalOnProperty("llmgw.providers.openai.enabled", havingValue = "true")
    fun openAiEmbeddingAdapter(
        properties: OpenAiProperties,
        mapper: JsonMapper,
        clock: Clock,
    ): OpenAiEmbeddingAdapter =
        OpenAiEmbeddingAdapter(properties.toEmbeddingAdapterConfig(), mapper, clock).also { it.warmUp() }

    // Only OpenAI: Anthropic has no embedding model, and an empty map is a valid start (ADR-GW-05-003).
    @Bean
    fun embeddingModelPort(openai: ObjectProvider<OpenAiEmbeddingAdapter>): EmbeddingModelPort =
        RoutingEmbeddingModelPort(buildMap { openai.ifAvailable { put(ProviderId(OPENAI), it) } })

    // The adapter's lane; a start with no embedding adapter has only the route's refusals to compute (ADR-GW-05-012).
    @Bean
    fun embeddingCpuPort(openai: ObjectProvider<OpenAiEmbeddingAdapter>): EmbeddingCpuPort =
        openai.ifAvailable?.lane ?: CpuLane("embeddings-edge-cpu", 1, EDGE_LANE_QUEUE, EDGE_LANE_DEADLINE)

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

        // Only refusals run here, and the budget bounds the bodies that wait.
        const val EDGE_LANE_QUEUE = 256

        // No adapter, so no validated embeddings.request-timeout. One parse of 10 MB takes under a second.
        val EDGE_LANE_DEADLINE: Duration = Duration.ofSeconds(30)
    }
}
