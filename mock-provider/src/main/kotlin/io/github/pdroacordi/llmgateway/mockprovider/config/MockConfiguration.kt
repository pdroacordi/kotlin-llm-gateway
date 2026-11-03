package io.github.pdroacordi.llmgateway.mockprovider.config

import io.github.pdroacordi.llmgateway.mockprovider.admin.AdminHandlers
import io.github.pdroacordi.llmgateway.mockprovider.admin.ScenarioJson
import io.github.pdroacordi.llmgateway.mockprovider.anthropic.AnthropicHandler
import io.github.pdroacordi.llmgateway.mockprovider.anthropic.AnthropicRenderer
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Behaviour
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ContentSpec
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Latency
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ScenarioStore
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.UsageSpec
import io.github.pdroacordi.llmgateway.mockprovider.generation.CompletionPlanner
import io.github.pdroacordi.llmgateway.mockprovider.generation.Embedder
import io.github.pdroacordi.llmgateway.mockprovider.generation.TextGenerator
import io.github.pdroacordi.llmgateway.mockprovider.journal.CredentialFingerprint
import io.github.pdroacordi.llmgateway.mockprovider.journal.RequestJournal
import io.github.pdroacordi.llmgateway.mockprovider.openai.EmbeddingLimits
import io.github.pdroacordi.llmgateway.mockprovider.openai.ModelList
import io.github.pdroacordi.llmgateway.mockprovider.openai.OpenAiHandlers
import io.github.pdroacordi.llmgateway.mockprovider.openai.OpenAiRenderer
import io.github.pdroacordi.llmgateway.mockprovider.web.ArrivalWebFilter
import io.github.pdroacordi.llmgateway.mockprovider.web.BodyCapture
import io.github.pdroacordi.llmgateway.mockprovider.web.Credentials
import io.github.pdroacordi.llmgateway.mockprovider.web.ErrorBodies
import io.github.pdroacordi.llmgateway.mockprovider.web.Generation
import io.github.pdroacordi.llmgateway.mockprovider.web.MockRoutes
import io.github.pdroacordi.llmgateway.mockprovider.web.Serving
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class MockConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun scenarioStore(properties: MockProperties): ScenarioStore {
        val defaults = properties.defaults
        return ScenarioStore(
            Behaviour(
                latency =
                    Latency(
                        ttftMs = defaults.latency.ttft.toMillis(),
                        perTokenMs = defaults.latency.perToken.toMillis(),
                        jitterMs = defaults.latency.jitter.toMillis(),
                    ),
                content = ContentSpec(mode = defaults.contentMode, completionTokens = defaults.completionTokens),
                usage = UsageSpec(omit = false, cachedTokens = 0, cacheCreationTokens = 0),
            ),
        )
    }

    @Bean
    fun requestJournal(
        properties: MockProperties,
        clock: Clock,
    ): RequestJournal = RequestJournal(properties.journal.capacity, clock)

    @Bean
    fun credentialFingerprint(): CredentialFingerprint = CredentialFingerprint()

    @Bean
    fun arrivalWebFilter(
        journal: RequestJournal,
        fingerprint: CredentialFingerprint,
        properties: MockProperties,
    ): ArrivalWebFilter {
        // CoWebFilter awaits the chain through kotlinx-coroutines-reactive, whose ReactiveFlowKt reads
        // META-INF/services from the jars (ServiceLoader) in its static initializer. Initialised here, not on the
        // first request's event loop thread (BlockHound: RandomAccessFile#readBytes).
        Class.forName(REACTIVE_FLOW)
        return ArrivalWebFilter(
            journal,
            fingerprint,
            BodyCapture(properties.journal.captureBodies, properties.journal.maxBodyBytes),
        )
    }

    @Bean
    fun openAiRenderer(mapper: JsonMapper): OpenAiRenderer = OpenAiRenderer(mapper)

    @Bean
    fun anthropicRenderer(mapper: JsonMapper): AnthropicRenderer = AnthropicRenderer(mapper)

    @Bean
    fun errorBodies(
        openAi: OpenAiRenderer,
        anthropic: AnthropicRenderer,
    ): ErrorBodies = ErrorBodies(openAi, anthropic)

    @Bean
    fun generation(
        mapper: JsonMapper,
        clock: Clock,
        store: ScenarioStore,
        bodies: ErrorBodies,
        properties: MockProperties,
    ): Generation =
        Generation(
            mapper = mapper,
            clock = clock,
            store = store,
            planner = CompletionPlanner(TextGenerator()),
            serving = Serving(bodies, properties.maxHold.toMillis()),
            credentials = Credentials(properties.auth.requireCredential),
        )

    @Bean
    fun mockRoutes(
        generation: Generation,
        openAi: OpenAiRenderer,
        anthropic: AnthropicRenderer,
        journal: RequestJournal,
        properties: MockProperties,
    ): RouterFunction<ServerResponse> {
        val embeddings = properties.embeddings
        val handlers =
            OpenAiHandlers(
                generation,
                openAi,
                Embedder(),
                EmbeddingLimits(embeddings.defaultDimensions, embeddings.maxDimensions),
                ModelList(properties.models, generation.clock.instant().epochSecond),
            )
        val admin =
            AdminHandlers(
                generation.mapper,
                generation.store,
                journal,
                ScenarioJson(generation.mapper, properties.maxHold.toMillis()),
            )
        return MockRoutes.routes(handlers, AnthropicHandler(generation, anthropic), admin)
    }

    private companion object {
        const val REACTIVE_FLOW = "kotlinx.coroutines.reactive.ReactiveFlowKt"
    }
}
