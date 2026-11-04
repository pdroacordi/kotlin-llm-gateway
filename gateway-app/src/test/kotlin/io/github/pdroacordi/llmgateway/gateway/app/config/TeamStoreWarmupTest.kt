package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.core.error.TeamStoreFailure
import io.github.pdroacordi.llmgateway.gateway.core.team.InMemoryTeamStore
import io.github.pdroacordi.llmgateway.gateway.core.team.KeyCredential
import io.github.pdroacordi.llmgateway.gateway.core.team.KeyDigest
import io.github.pdroacordi.llmgateway.gateway.core.team.StoreResult
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamStorePort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TeamStoreWarmupTest.Wiring::class)
class TeamStoreWarmupTest(
    @Autowired private val atServerStart: LookupsAtServerStart,
) {
    /** The lookups the store had seen when the server began taking requests. */
    class LookupsAtServerStart(
        private val store: InMemoryTeamStore,
    ) : ApplicationListener<WebServerInitializedEvent> {
        @Volatile
        var seen: Int = -1
            private set

        override fun onApplicationEvent(event: WebServerInitializedEvent) {
            seen = store.findKeyCalls
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Wiring {
        @Bean
        fun lookupsAtServerStart(store: InMemoryTeamStore): LookupsAtServerStart = LookupsAtServerStart(store)
    }

    /** Fails its first [failures] lookups, as a cold pool's `max-acquire-time` does. */
    private class FailsAtFirst(
        private val failures: Int,
        private val delegate: InMemoryTeamStore = InMemoryTeamStore(),
    ) : TeamStorePort by delegate {
        var calls = 0
            private set

        override suspend fun findKey(digest: KeyDigest): StoreResult<KeyCredential?> {
            calls += 1
            return if (calls <= failures) StoreResult.Failed(TeamStoreFailure.Unavailable) else StoreResult.Ok(null)
        }
    }

    @Test
    fun `one lookup runs before the server takes requests`() {
        assertEquals(1, atServerStart.seen)
    }

    @Test
    fun `a failed lookup is tried again until one succeeds`() =
        runTest {
            val store = FailsAtFirst(failures = 3)

            TeamStoreWarmup(store, 10.seconds).warm()

            assertEquals(4, store.calls)
        }

    @Test
    fun `a store outage ends the warm-up at its deadline and does not stop the start`() =
        runTest {
            val store = InMemoryTeamStore().also { it.available = false }

            assertDoesNotThrow { TeamStoreWarmup(store, 10.seconds).warm() }

            assertEquals(10.seconds.inWholeMilliseconds, currentTime)
            assertTrue(store.findKeyCalls > 1) { "calls: ${store.findKeyCalls}" }
        }

    @Test
    fun `a hung store ends the warm-up at its deadline`() =
        runTest {
            val hung =
                object : TeamStorePort by InMemoryTeamStore() {
                    override suspend fun findKey(digest: KeyDigest): StoreResult<KeyCredential?> = awaitCancellation()
                }

            TeamStoreWarmup(hung, 500.milliseconds).warm()

            assertEquals(500, currentTime)
        }
}
