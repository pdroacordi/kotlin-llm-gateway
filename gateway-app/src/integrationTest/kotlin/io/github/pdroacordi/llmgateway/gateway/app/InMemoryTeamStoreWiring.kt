package io.github.pdroacordi.llmgateway.gateway.app

import io.github.pdroacordi.llmgateway.gateway.core.team.InMemoryTeamStore
import io.github.pdroacordi.llmgateway.gateway.core.team.KeyHasher
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/** This suite sees no `src/test` classes, so it has its own; `llmgw.test.team-store=postgres` turns it off. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("llmgw.test.team-store", havingValue = "memory", matchIfMissing = true)
class InMemoryTeamStoreWiring {
    @Bean
    @Primary
    fun inMemoryTeamStore(hasher: KeyHasher): InMemoryTeamStore = InMemoryTeamStore().also { it.seed(hasher) }
}
