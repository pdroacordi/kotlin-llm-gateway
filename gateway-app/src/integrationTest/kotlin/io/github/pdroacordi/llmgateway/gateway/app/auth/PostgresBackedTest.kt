package io.github.pdroacordi.llmgateway.gateway.app.auth

import io.github.pdroacordi.llmgateway.gateway.app.chat.MockBackedTest
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.github.pdroacordi.llmgateway.testsupport.PostgresDatabase
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real store through the app, Flyway included. Each subclass names its own database in its own
 * `@DynamicPropertySource`, because a superclass's dynamic property always wins
 * (docs/knowledge/gotchas/dynamic-property-source-superclass-registers-last.md).
 */
@TestPropertySource(properties = ["llmgw.test.team-store=postgres", "spring.flyway.enabled=true"])
open class PostgresBackedTest : MockBackedTest() {
    protected val admin: WebTestClient by lazy {
        gateway
            .mutate()
            .defaultHeaders { it.set(HttpHeaders.AUTHORIZATION, TestKeys.ADMIN_BEARER) }
            .build()
    }

    protected fun uniqueName(): String = "name-${NAMES.incrementAndGet()}"

    private fun created(
        uri: String,
        body: String,
    ): JsonNode {
        val bytes =
            admin
                .post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.encodeToByteArray())
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(ByteArray::class.java)
                .returnResult()
                .responseBody
        return mapper.readTree(checkNotNull(bytes))
    }

    protected fun createTeam(): String =
        created("/admin/v1/teams", """{"name":"${uniqueName()}"}""").get("id").stringValue()

    protected fun createKey(team: String): JsonNode =
        created("/admin/v1/teams/$team/keys", """{"name":"${uniqueName()}"}""")

    protected fun revoke(key: JsonNode) {
        admin
            .post()
            .uri("/admin/v1/teams/${key.get("team_id").stringValue()}/keys/${key.get("id").stringValue()}/revoke")
            .exchange()
            .expectStatus()
            .isOk
    }

    protected fun chatAs(key: String): WebTestClient.ResponseSpec =
        chat(headers = mapOf(HttpHeaders.AUTHORIZATION to "Bearer $key"))

    companion object {
        private val NAMES = AtomicInteger()

        fun register(
            registry: DynamicPropertyRegistry,
            database: PostgresDatabase,
        ) {
            registry.add("spring.flyway.url") { database.jdbcUrl }
            registry.add("spring.flyway.user") { database.username }
            registry.add("spring.flyway.password") { database.password }
            registry.add("spring.r2dbc.url") { database.r2dbcUrl }
            registry.add("spring.r2dbc.username") { database.username }
            registry.add("spring.r2dbc.password") { database.password }
        }
    }
}
