package io.github.pdroacordi.llmgateway.gateway.app.auth

import io.github.pdroacordi.llmgateway.gateway.app.chat.FakeChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.team.InMemoryTeamStore
import io.github.pdroacordi.llmgateway.gateway.core.team.KeyHasher
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamStorePort
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.github.pdroacordi.llmgateway.gateway.infra.cache.CachingTeamStore
import io.github.pdroacordi.llmgateway.gateway.infra.cache.TeamCacheConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** The fake behind the real decorator, with a time source the test moves. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["llmgw.test.team-store=cached"],
)
@Import(FakeChatModelPort.Wiring::class, CachedKeyOutageRouteTest.Wiring::class)
class CachedKeyOutageRouteTest(
    @LocalServerPort port: Int,
    @Autowired private val store: InMemoryTeamStore,
    @Autowired private val clock: MovableClock,
) {
    class MovableClock : Clock() {
        @Volatile
        var now: Instant = TestKeys.CREATED_AT

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Wiring {
        @Bean
        @Primary
        fun movableClock(): MovableClock = MovableClock()

        @Bean
        fun fakeBehindTheCache(hasher: KeyHasher): InMemoryTeamStore = InMemoryTeamStore().also { it.seed(hasher) }

        @Bean
        @Primary
        fun cachedStore(store: InMemoryTeamStore): TeamStorePort =
            CachingTeamStore(store, TeamCacheConfig(30.seconds, 100, 5.seconds, 100), NANOS::get)
    }

    private val client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:$port").build()

    @BeforeEach
    fun reset() {
        store.available = true
        store.seed(TestKeys.hasher())
        NANOS.addAndGet(31.seconds.inWholeNanoseconds)
    }

    private fun chat(token: String): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer $token")
            .bodyValue("""{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}]}""".encodeToByteArray())
            .exchange()

    @Test
    fun `during an outage a cached key gets 200, an uncached one 503, and an expired entry 503`() {
        chat(TestKeys.TEAM_KEY).expectStatus().isOk
        store.available = false

        chat(TestKeys.TEAM_KEY).expectStatus().isOk
        chat(TestKeys.UNKNOWN_KEY).expectStatus().isEqualTo(503)

        NANOS.addAndGet(31.seconds.inWholeNanoseconds)
        chat(TestKeys.TEAM_KEY).expectStatus().isEqualTo(503)
    }

    @Test
    fun `a cached key past its expiry is refused with no store call`() {
        // Core judges expiry on every request, so the seeded key's cache entry cannot outlive it.
        val expiry = clock.now.plusSeconds(60)
        store.seed(TestKeys.hasher(), expiresAt = expiry)
        val before = store.findKeyCalls
        chat(TestKeys.TEAM_KEY).expectStatus().isOk
        val afterMiss = store.findKeyCalls
        clock.now = expiry

        chat(TestKeys.TEAM_KEY).expectStatus().isUnauthorized

        assertEquals(before + 1, afterMiss)
        assertEquals(afterMiss, store.findKeyCalls)
    }

    private companion object {
        val NANOS = AtomicLong()
    }
}
