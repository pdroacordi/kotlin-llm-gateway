package io.github.pdroacordi.llmgateway.gateway.app.auth

import io.github.pdroacordi.llmgateway.gateway.app.isolatedGateway
import io.github.pdroacordi.llmgateway.testsupport.SharedMockProvider
import io.github.pdroacordi.llmgateway.testsupport.SharedPostgres
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

/** Two replicas on one database: this test's context, and one more it starts. */
@TestPropertySource(properties = ["llmgw.auth.cache.ttl=5s"])
class RevocationLagTest : PostgresBackedTest() {
    private fun replica() =
        isolatedGateway().run(
            "--server.port=0",
            "--llmgw.test.team-store=postgres",
            "--llmgw.auth.cache.ttl=5s",
            "--spring.flyway.enabled=true",
            "--spring.flyway.url=${DATABASE.jdbcUrl}",
            "--spring.flyway.user=${DATABASE.username}",
            "--spring.flyway.password=${DATABASE.password}",
            "--spring.r2dbc.url=${DATABASE.r2dbcUrl}",
            "--spring.r2dbc.username=${DATABASE.username}",
            "--spring.r2dbc.password=${DATABASE.password}",
            "--llmgw.providers.openai.base-url=${SharedMockProvider.baseUrl}/v1",
            "--llmgw.providers.openai.api-key=$KEY",
        )

    private fun chatOn(
        port: String,
        key: String,
    ): Int =
        WebTestClient
            .bindToServer()
            .baseUrl("http://127.0.0.1:$port")
            .build()
            .post()
            .uri(ROUTE)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer $key")
            .bodyValue(MINIMAL.encodeToByteArray())
            .exchange()
            .expectBody()
            .returnResult()
            .status
            .value()

    @Test
    fun `a revoke holds at once here and within the TTL on the other replica`() {
        val team = createTeam()
        val warm = createKey(team).get("key").stringValue()
        val key = createKey(team)
        val plain = key.get("key").stringValue()

        replica().use { other ->
            val port = checkNotNull(other.environment.getProperty("local.server.port"))
            // A first call on each replica pays the cold paths, so the TTL below is not spent on them.
            chatAs(warm).expectStatus().isOk
            check(chatOn(port, warm) == 200) { "the other replica must answer a good key" }
            chatAs(plain).expectStatus().isOk
            check(chatOn(port, plain) == 200) { "the other replica must cache the key first" }

            revoke(key)

            chatAs(plain).expectStatus().isUnauthorized
            assertEquals(200, chatOn(port, plain)) { "the other replica's cached entry must outlive the revoke" }
            await()
                .atMost(TTL.plusSeconds(1))
                .pollInterval(Duration.ofMillis(100))
                .until { chatOn(port, plain) == 401 }
        }
        // The other replica has closed: this context's provider client must still reach the provider.
        chatAs(warm).expectStatus().isOk
    }

    private companion object {
        val TTL: Duration = Duration.ofSeconds(5)
        val DATABASE = SharedPostgres.database("revocation_lag")

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            register(registry, DATABASE)
        }
    }
}
