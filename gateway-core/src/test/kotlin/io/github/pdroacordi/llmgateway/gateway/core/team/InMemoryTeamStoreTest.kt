package io.github.pdroacordi.llmgateway.gateway.core.team

class InMemoryTeamStoreTest : TeamStoreContract() {
    override fun store(): TeamStorePort = InMemoryTeamStore()
}
