package io.github.pdroacordi.llmgateway.gateway.app.auth

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.core.error.TeamStoreFailure
import io.github.pdroacordi.llmgateway.gateway.core.error.Unauthenticated
import io.github.pdroacordi.llmgateway.gateway.core.team.InMemoryTeamStore
import io.github.pdroacordi.llmgateway.gateway.core.team.OperatorAuthenticator
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamAuthenticator
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.CoroutineContext

// Every row of the two authentication tables of GW-04 section 4, with the mock exchange.
class AuthWebFilterTest {
    private val hasher = TestKeys.hasher()
    private val store = InMemoryTeamStore().also { it.seed(hasher) }
    private val filter =
        AuthWebFilter(
            TeamAuthenticator(hasher, store, Clock.systemUTC()),
            OperatorAuthenticator(hasher, TestKeys.ADMIN_KEY),
        )
    private var reached = false

    private val chain = WebFilterChain { Mono.fromRunnable { reached = true } }

    private fun exchange(
        path: String,
        vararg authorization: String,
    ): MockServerWebExchange =
        MockServerWebExchange.from(
            MockServerHttpRequest
                .method(HttpMethod.POST, URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, *authorization),
        )

    private fun run(exchange: ServerWebExchange) {
        filter.filter(exchange, chain).block(Duration.ofSeconds(5))
    }

    private fun assertRejected(
        expected: Any,
        exchange: ServerWebExchange,
    ) {
        val failure = assertThrows<GatewayErrorException> { run(exchange) }

        assertEquals(expected, failure.error)
        assertFalse(reached, "the chain must not run")
    }

    private fun teamOf(exchange: ServerWebExchange): TeamContext? =
        exchange.getAttribute<CoroutineContext>(CoWebFilter.COROUTINE_CONTEXT_ATTRIBUTE)?.get(TeamContext)

    @Test
    fun `it runs right after the request context filter`() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 1, filter.order)
    }

    @Test
    fun `a team key on v1 continues with its team`() {
        val exchange = exchange("/v1/chat/completions", TestKeys.BEARER)

        run(exchange)

        assertTrue(reached)
        assertEquals(TestKeys.TEAM_ID, teamOf(exchange)?.team)
    }

    @Test
    fun `the admin key on admin continues with no team`() {
        val exchange = exchange("/admin/v1/teams", TestKeys.ADMIN_BEARER)

        run(exchange)

        assertTrue(reached)
        assertNull(teamOf(exchange))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/actuator/health", "/does-not-exist", "/", "/v2/x", "/v11/chat", "/administrator"])
    fun `any other path continues with no credential`(path: String) {
        run(exchange(path))

        assertTrue(reached)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/v1", "/v1/", "/v1/does-not-exist", "/v%31/chat/completions", "/v1;a=b/chat/completions"])
    fun `every form of a v1 path needs a key`(path: String) {
        assertRejected(Unauthenticated.MissingKey, exchange(path))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/admin", "/admin/v1/teams", "/%61dmin/v1/teams", "/admin;a=b/v1/teams"])
    fun `every form of an admin path needs the admin key`(path: String) {
        assertRejected(Unauthenticated.MissingKey, exchange(path))
    }

    @Test
    fun `a non-Bearer scheme is missing`() {
        assertRejected(Unauthenticated.MissingKey, exchange("/v1/x", "Basic abc"))
    }

    @Test
    fun `two Authorization lines are invalid`() {
        assertRejected(Unauthenticated.InvalidKey, exchange("/v1/x", TestKeys.BEARER, TestKeys.BEARER))
    }

    @Test
    fun `x-api-key, api-key and access_token are not credentials`() {
        val request =
            MockServerHttpRequest
                .post("/v1/x?access_token=${TestKeys.TEAM_KEY}")
                .header("x-api-key", TestKeys.TEAM_KEY)
                .header("api-key", TestKeys.TEAM_KEY)

        assertRejected(Unauthenticated.MissingKey, MockServerWebExchange.from(request))
    }

    @Test
    fun `a team key on admin is invalid`() {
        assertRejected(Unauthenticated.InvalidKey, exchange("/admin/v1/teams", TestKeys.BEARER))
    }

    @Test
    fun `the admin key on v1 is invalid`() {
        assertRejected(Unauthenticated.InvalidKey, exchange("/v1/x", TestKeys.ADMIN_BEARER))
    }

    @Test
    fun `a store outage is the store's error, not a 401`() {
        store.available = false

        assertRejected(TeamStoreFailure.Unavailable, exchange("/v1/x", TestKeys.BEARER))
    }

    @Test
    fun `an admin response is no-store, a rejection too, and a v1 one is not`() {
        val rejected = exchange("/admin/v1/teams")
        val accepted = exchange("/admin/v1/teams", TestKeys.ADMIN_BEARER)
        val chat = exchange("/v1/x", TestKeys.BEARER)

        assertThrows<GatewayErrorException> { run(rejected) }
        run(accepted)
        run(chat)

        assertEquals("no-store", rejected.response.headers.cacheControl)
        assertEquals("no-store", accepted.response.headers.cacheControl)
        assertNull(chat.response.headers.cacheControl)
    }
}
