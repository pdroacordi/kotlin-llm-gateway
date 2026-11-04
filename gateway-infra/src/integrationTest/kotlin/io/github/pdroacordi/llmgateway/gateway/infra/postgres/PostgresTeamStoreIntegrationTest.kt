package io.github.pdroacordi.llmgateway.gateway.infra.postgres

import io.github.pdroacordi.llmgateway.gateway.core.team.TeamStoreContract
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamStorePort
import io.github.pdroacordi.llmgateway.testsupport.SharedPostgres
import org.junit.jupiter.api.BeforeEach
import kotlin.time.Duration.Companion.seconds

class PostgresTeamStoreIntegrationTest : TeamStoreContract() {
    @BeforeEach
    fun empty() {
        TestPostgres.execute(DATABASE, "TRUNCATE team_keys, teams")
    }

    override fun store(): TeamStorePort = PostgresTeamStore(POOL, 5.seconds)

    private companion object {
        val DATABASE = SharedPostgres.database("team_store_contract").also { TestPostgres.migrate(it) }
        val POOL = TestPostgres.pool(DATABASE)
    }
}
