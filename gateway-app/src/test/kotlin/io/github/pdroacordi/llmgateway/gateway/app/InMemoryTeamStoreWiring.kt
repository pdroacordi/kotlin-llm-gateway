package io.github.pdroacordi.llmgateway.gateway.app

import io.github.pdroacordi.llmgateway.gateway.core.team.InMemoryTeamStore
import io.github.pdroacordi.llmgateway.gateway.core.team.KeyHasher
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/** Scanned on purpose: every context gets the fake, with `TestKeys.TEAM_KEY` seeded (ADR-GW-04-010). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("llmgw.test.team-store", havingValue = "memory", matchIfMissing = true)
class InMemoryTeamStoreWiring {
    @Bean
    @Primary
    fun inMemoryTeamStore(hasher: KeyHasher): InMemoryTeamStore = InMemoryTeamStore().also { it.seed(hasher) }
}
