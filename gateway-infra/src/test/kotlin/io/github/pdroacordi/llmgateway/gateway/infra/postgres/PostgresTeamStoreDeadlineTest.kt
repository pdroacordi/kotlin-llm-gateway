package io.github.pdroacordi.llmgateway.gateway.infra.postgres

import io.github.pdroacordi.llmgateway.gateway.core.error.TeamStoreFailure
import io.github.pdroacordi.llmgateway.gateway.core.team.StoreResult
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The filter's coroutine runs under `Unconfined`, so the thread that ends the wait also serves the 503. */
class PostgresTeamStoreDeadlineTest {
    private val hung =
        object : ConnectionFactory {
            override fun create(): Publisher<out Connection> = Mono.never()

            override fun getMetadata(): ConnectionFactoryMetadata = ConnectionFactoryMetadata { "PostgreSQL" }
        }

    // Unconfined is what CoWebFilter starts with, so the dispatcher is the point and is not injected.
    @Suppress("InjectDispatcher")
    @Test
    fun `a lookup past its deadline resumes on a thread BlockHound watches`() {
        // Longer than the cold class loading before the first suspension, or the deadline ends on the caller's thread.
        val store = PostgresTeamStore(hung, 2.seconds)

        val thread =
            mono(Dispatchers.Unconfined) {
                assertEquals(StoreResult.Failed(TeamStoreFailure.Unavailable), store.findKey(DIGEST))
                Thread.currentThread().name
            }.block(Duration.ofSeconds(5))

        assertTrue(thread.orEmpty().startsWith("parallel-")) { "resumed on $thread" }
    }

    private companion object {
        val DIGEST = TestKeys.hasher().digest("nobody")
    }
}
