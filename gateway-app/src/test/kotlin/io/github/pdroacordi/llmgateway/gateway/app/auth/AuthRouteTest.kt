package io.github.pdroacordi.llmgateway.gateway.app.auth

import io.github.pdroacordi.llmgateway.gateway.app.chat.FakeChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.team.InMemoryTeamStore
import io.github.pdroacordi.llmgateway.gateway.core.team.KeyHasher
import io.github.pdroacordi.llmgateway.gateway.core.team.NewTeamKey
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamKey
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamKeyId
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.URI
import java.time.Instant
import kotlin.uuid.Uuid

/** Over a socket: what a client sees, envelope and headers included. The rows are GW-04 section 3. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeChatModelPort.Wiring::class)
class AuthRouteTest(
    @LocalServerPort private val port: Int,
    @Autowired private val provider: FakeChatModelPort,
    @Autowired private val store: InMemoryTeamStore,
    @Autowired private val hasher: KeyHasher,
) {
    private val client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:$port").build()

    @BeforeEach
    fun reset() {
        provider.reset()
        store.available = true
    }

    private fun chat(vararg authorization: String): WebTestClient.ResponseSpec =
        client
            .post()
            .uri(ROUTE)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { headers -> authorization.forEach { headers.add("Authorization", it) } }
            .bodyValue(MINIMAL.encodeToByteArray())
            .exchange()

    private fun addKey(
        token: String,
        name: String,
        expiresAt: Instant? = null,
        revoke: Boolean = false,
    ) = runBlocking {
        val id = TeamKeyId(Uuid.random())
        val key = TeamKey(id, TestKeys.TEAM_ID, TestKeys.name(name), "h", TestKeys.CREATED_AT, expiresAt, null)
        store.createKey(NewTeamKey(key, hasher.digest(token), 1))
        if (revoke) store.revokeKey(TestKeys.TEAM_ID, id, TestKeys.CREATED_AT)
    }

    private fun WebTestClient.ResponseSpec.unauthorized(
        code: String,
        challenge: String,
    ): EntityExchangeResult<ByteArray> {
        val result =
            expectStatus()
                .isUnauthorized
                .expectHeader()
                .valueEquals("WWW-Authenticate", challenge)
                .expectHeader()
                .exists("x-request-id")
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo(code)
                .jsonPath("$.error.type")
                .isEqualTo("invalid_request_error")
                .returnResult()
        assertTrue(provider.calls.isEmpty()) { "a rejected request reached the provider" }
        return result
    }

    private fun WebTestClient.ResponseSpec.missing() = unauthorized("missing_api_key", "Bearer")

    private fun WebTestClient.ResponseSpec.invalid() = unauthorized("invalid_api_key", INVALID_CHALLENGE)

    @Test
    fun `a valid key answers 200 and the call carries its team`() {
        chat(TestKeys.BEARER).expectStatus().isOk

        assertEquals(TestKeys.TEAM_ID, provider.calls.single().team)
    }

    @Test
    fun `a valid key streams and the call carries its team`() {
        client
            .post()
            .uri(ROUTE)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", TestKeys.BEARER)
            .bodyValue(STREAM.encodeToByteArray())
            .exchange()
            .expectStatus()
            .isOk

        assertEquals(TestKeys.TEAM_ID, provider.calls.single().team)
    }

    @Test
    fun `no credential gives 401 missing_api_key`() {
        chat().missing()
    }

    @ParameterizedTest
    @ValueSource(strings = ["Basic dXNlcjpwYXNz", "Bearer", "Token abc"])
    fun `a non-Bearer scheme or an empty token gives 401 missing_api_key`(authorization: String) {
        chat(authorization).missing()
    }

    @Test
    fun `unknown, revoked, expired, malformed and doubled credentials give one 401, equal but for the request ID`() {
        addKey(REVOKED, "revoked", revoke = true)
        addKey(EXPIRED, "expired", expiresAt = Instant.parse("2020-01-01T00:00:00Z"))

        val bodies =
            listOf(
                chat("Bearer ${TestKeys.UNKNOWN_KEY}"),
                chat("Bearer $REVOKED"),
                chat("Bearer $EXPIRED"),
                chat("Bearer not-a-key"),
                chat(TestKeys.BEARER, TestKeys.BEARER),
            ).map { checkNotNull(it.invalid().responseBody).decodeToString() }

        assertEquals(1, bodies.toSet().size) { bodies.toString() }
    }

    @Test
    fun `x-api-key and an access_token query parameter are not credentials`() {
        client
            .post()
            .uri("$ROUTE?access_token=${TestKeys.TEAM_KEY}")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", TestKeys.TEAM_KEY)
            .bodyValue(MINIMAL.encodeToByteArray())
            .exchange()
            .missing()
    }

    @Test
    fun `the admin key on v1 gives 401 invalid_api_key`() {
        chat(TestKeys.ADMIN_BEARER).invalid()
    }

    @Test
    fun `a team key on admin gives 401 invalid_api_key`() {
        client
            .get()
            .uri("/admin/v1/teams")
            .header("Authorization", TestKeys.BEARER)
            .exchange()
            .invalid()
    }

    @ParameterizedTest
    @MethodSource("unauthenticatedRequests")
    fun `with no key, an unknown path, a wrong method and a preflight get 401`(
        method: String,
        path: String,
    ) {
        val result =
            client
                .method(HttpMethod.valueOf(method))
                .uri(URI.create("http://127.0.0.1:$port$path"))
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .missing()

        assertTrue(result.responseHeaders.headerNames().none { it.lowercase().startsWith("access-control-") })
    }

    @Test
    fun `with a key an unknown path stays 404`() {
        client
            .get()
            .uri("/v1/does-not-exist")
            .header("Authorization", TestKeys.BEARER)
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `with a key a wrong method stays 405`() {
        client
            .get()
            .uri(ROUTE)
            .header("Authorization", TestKeys.BEARER)
            .exchange()
            .expectStatus()
            .isEqualTo(405)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/v%31/chat/completions", "/v1;a=b/chat/completions"])
    fun `a percent-encoded or parameter form of the route needs a key`(path: String) {
        client
            .post()
            .uri(URI.create("http://127.0.0.1:$port$path"))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(MINIMAL.encodeToByteArray())
            .exchange()
            .missing()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/v1//chat/completions", "/v1/chat//completions", "/v1/chat/completions/"])
    fun `a doubled or trailing slash in the route needs a key`(path: String) {
        client
            .post()
            .uri(URI.create("http://127.0.0.1:$port$path"))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(MINIMAL.encodeToByteArray())
            .exchange()
            .missing()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/admin//v1/teams", "/admin/v1//teams", "/admin/v1/teams/"])
    fun `a doubled or trailing slash in an admin route needs a key`(path: String) {
        client
            .get()
            .uri(URI.create("http://127.0.0.1:$port$path"))
            .exchange()
            .missing()
    }

    // Outside the auth scope, and outside the router's too: an open 404 is no bypass, a provider call would be.
    @ParameterizedTest
    @ValueSource(strings = ["//v1/chat/completions", "//admin/v1/teams"])
    fun `a leading doubled slash reaches no route`(path: String) {
        client
            .post()
            .uri(URI.create("http://127.0.0.1:$port$path"))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(MINIMAL.encodeToByteArray())
            .exchange()
            .expectStatus()
            .isNotFound

        assertTrue(provider.calls.isEmpty()) { "a request with no key reached the provider" }
    }

    @Test
    fun `health answers with no key`() {
        client
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `a store outage gives 503 team_store_unavailable, never 401, with the request ID in the body`() {
        store.available = false

        val result =
            chat(TestKeys.BEARER)
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("team_store_unavailable")
                .jsonPath("$.error.type")
                .isEqualTo("server_error")
                .returnResult()

        val requestId = checkNotNull(result.responseHeaders.getFirst("x-request-id"))
        assertTrue(requestId in checkNotNull(result.responseBody).decodeToString())
        assertFalse(result.responseHeaders.containsHeader("WWW-Authenticate"))
        assertTrue(provider.calls.isEmpty())
    }

    companion object {
        private const val ROUTE = "/v1/chat/completions"
        private const val MINIMAL = """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}]}"""
        private const val STREAM = """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],"stream":true}"""
        private const val INVALID_CHALLENGE = "Bearer error=\"invalid_token\""
        private const val REVOKED = "llmgw_test-team-key-not-a-real-secret-revoked0000"
        private const val EXPIRED = "llmgw_test-team-key-not-a-real-secret-expired0000"

        @JvmStatic
        fun unauthenticatedRequests(): List<Array<String>> =
            listOf(
                arrayOf("GET", "/v1/does-not-exist"),
                arrayOf("GET", ROUTE),
                arrayOf("DELETE", ROUTE),
                arrayOf("OPTIONS", ROUTE),
            )
    }
}
