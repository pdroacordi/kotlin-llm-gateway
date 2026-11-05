package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.app.config.OpenAiProperties
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelResolver
import io.github.pdroacordi.llmgateway.gateway.core.chat.UsageEstimator
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingCpuPort
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingModelPort
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingReplyBudget
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingReplyLimits
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import tools.jackson.databind.json.JsonMapper
import kotlin.time.toKotlinDuration

@Configuration(proxyBeanMethods = false)
class EmbeddingConfiguration {
    @Bean
    fun embeddingService(
        resolver: ModelResolver,
        port: EmbeddingModelPort,
        estimator: UsageEstimator,
    ): EmbeddingService = EmbeddingService(resolver, port, estimator)

    @Bean
    fun embeddingAdmission(properties: OpenAiProperties): EmbeddingAdmission {
        val embeddings = properties.embeddings
        val heapBytes = Runtime.getRuntime().maxMemory()
        embeddings.heapWarning(heapBytes)?.let { log.warn(it) }
        return EmbeddingAdmission(
            EmbeddingReplyBudget(embeddings.inFlightBytes(heapBytes)),
            EmbeddingReplyLimits(embeddings.defaultDimensions, embeddings.maxResponseSize.toBytes().toInt()),
            embeddings.busyRetryAfter.toKotlinDuration(),
        )
    }

    @Bean
    fun embeddingReplyFilter(): EmbeddingReplyFilter = EmbeddingReplyFilter()

    @Bean
    fun embeddingHandler(
        service: EmbeddingService,
        mapper: JsonMapper,
        cpu: EmbeddingCpuPort,
        admission: EmbeddingAdmission,
    ): EmbeddingHandler = EmbeddingHandler(service, mapper, cpu, admission)

    @Bean
    fun embeddingRoutes(handler: EmbeddingHandler): RouterFunction<ServerResponse> = EmbeddingRoutes.routes(handler)

    private companion object {
        private val log = LoggerFactory.getLogger(EmbeddingConfiguration::class.java)
    }
}
