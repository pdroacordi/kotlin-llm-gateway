package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.app.auth.AuthProperties
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamStorePort
import io.github.pdroacordi.llmgateway.gateway.infra.cache.CachingTeamStore
import io.github.pdroacordi.llmgateway.gateway.infra.cache.TeamCacheConfig
import io.github.pdroacordi.llmgateway.gateway.infra.postgres.PostgresTeamStore
import io.r2dbc.spi.ConnectionFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.toKotlinDuration

/** The only `main` code that names `.gateway.infra` (ARCH-12). */
@Configuration(proxyBeanMethods = false)
class TeamStoreConfiguration {
    @Bean
    fun teamStorePort(
        connections: ConnectionFactory,
        properties: AuthProperties,
    ): TeamStorePort {
        properties.validate()
        val cache = properties.cache
        return CachingTeamStore(
            PostgresTeamStore(connections, properties.storeTimeout.toKotlinDuration()),
            TeamCacheConfig(
                cache.ttl.toKotlinDuration(),
                cache.maxSize,
                cache.negativeTtl.toKotlinDuration(),
                cache.negativeMaxSize,
            ),
        )
    }

    @Bean
    fun teamStoreWarmup(
        store: TeamStorePort,
        properties: AuthProperties,
    ): TeamStoreWarmup = TeamStoreWarmup(store, properties.warmupTimeout.toKotlinDuration())
}
