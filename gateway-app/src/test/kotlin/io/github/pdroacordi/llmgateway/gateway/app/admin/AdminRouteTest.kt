package io.github.pdroacordi.llmgateway.gateway.app.admin

import io.github.pdroacordi.llmgateway.gateway.app.chat.FakeChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** The five routes of GW-04 section 4, over a socket with the fake store. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeChatModelPort.Wiring::class)
class AdminRouteTest(
    @LocalServerPort port: Int,
) {
    private val mapper = JsonMapper.builder().build()
    private val raw = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:$port").build()
    private val admin = raw.mutate().defaultHeader("Authorization", TestKeys.ADMIN_BEARER).build()

    private fun post(
        uri: String,
        body: String?,
    ): WebTestClient.ResponseSpec {
        val request = admin.post().uri(uri)
        return if (body == null) {
            request.exchange()
        } else {
            request
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.encodeToByteArray())
                .exchange()
        }
    }

    private fun get(uri: String): WebTestClient.ResponseSpec = admin.get().uri(uri).exchange()

    private fun WebTestClient.ResponseSpec.result(status: Int): EntityExchangeResult<ByteArray> =
        expectStatus()
            .isEqualTo(status)
            .expectBody()
            .returnResult()

    private fun EntityExchangeResult<ByteArray>.tree(): JsonNode = mapper.readTree(checkNotNull(responseBody))

    private fun EntityExchangeResult<ByteArray>.text(): String = checkNotNull(responseBody).decodeToString()

    private fun WebTestClient.ResponseSpec.json(status: Int): JsonNode = result(status).tree()

    private fun WebTestClient.ResponseSpec.error(
        status: Int,
        code: String,
        param: String? = null,
    ) {
        val error = json(status).get("error")

        assertEquals(code, error.get("code").stringValue())
        assertEquals(param, error.get("param").takeUnless { it.isNull }?.stringValue())
    }

    private fun uniqueName(): String = "name-${NAMES.incrementAndGet()}"

    private fun team(): String =
        post(TEAMS, """{"name":"${uniqueName()}"}""")
            .json(201)
            .get("id")
            .stringValue()

    private fun key(
        team: String,
        name: String = uniqueName(),
    ): JsonNode = post("$TEAMS/$team/keys", """{"name":"$name"}""").json(201)

    private fun chat(key: String): WebTestClient.ResponseSpec =
        raw
            .post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer $key")
            .bodyValue("""{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}]}""".encodeToByteArray())
            .exchange()

    @Test
    fun `POST teams answers 201 with the team and no-store`() {
        val name = uniqueName()

        val result = post(TEAMS, """{"name":"$name"}""").result(201)

        val team = result.tree()
        assertEquals(setOf("id", "name", "created_at"), team.propertyNames().toSet())
        assertEquals(name, team.get("name").stringValue())
        assertEquals('7', team.get("id").stringValue()[VERSION_INDEX])
        Instant.parse(team.get("created_at").stringValue())
        assertEquals("no-store", result.responseHeaders.cacheControl)
        assertEquals(MediaType.APPLICATION_JSON, result.responseHeaders.contentType)
    }

    @Test
    fun `a team name in use gives 409`() {
        val name = uniqueName()
        post(TEAMS, """{"name":"$name"}""").result(201)

        post(TEAMS, """{"name":"$name"}""").error(409, "team_name_taken", "name")
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"name":"Upper"}""", """{"name":""}""", """{"name":1}""", """{"name":null}""", "{}"])
    fun `a bad team name gives 400 with param name`(body: String) {
        post(TEAMS, body).error(400, "invalid_request", "name")
    }

    @Test
    fun `any other member gives 400 with no param`() {
        post(TEAMS, """{"name":"${uniqueName()}","color":"red"}""").error(400, "invalid_request")
    }

    @Test
    fun `a failure message quotes no value`() {
        val text = post(TEAMS, """{"name":"Canary Value"}""").result(400).text()

        assertFalse("Canary Value" in text) { text }
    }

    @Test
    fun `a body that is not JSON gives 400 and another type 415`() {
        post(TEAMS, "not json").error(400, "invalid_body")
        admin
            .post()
            .uri(TEAMS)
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue("x".encodeToByteArray())
            .exchange()
            .error(415, "unsupported_media_type")
    }

    @Test
    fun `GET teams pages ascending by ID in the OpenAI list shape`() {
        val first = team()
        val second = team()
        val third = team()

        val page = get("$TEAMS?limit=2&after=$first").json(200)

        assertEquals("list", page.get("object").stringValue())
        assertEquals(listOf(second, third), page.get("data").values().map { it.get("id").stringValue() })
        assertEquals(second, page.get("first_id").stringValue())
        assertEquals(third, page.get("last_id").stringValue())
        assertFalse(page.get("has_more").booleanValue())
        assertTrue(
            get("$TEAMS?limit=1&after=$first")
                .json(200)
                .get("has_more")
                .booleanValue(),
        )
    }

    @Test
    fun `the default limit is 20 and an empty page has null ends`() {
        val last = team()

        val page = get("$TEAMS?after=$last").json(200)

        assertTrue(page.get("data").isEmpty)
        assertTrue(page.get("first_id").isNull)
        assertTrue(page.get("last_id").isNull)
        repeat(21) { team() }
        assertEquals(
            20,
            get("$TEAMS?after=$last")
                .json(200)
                .get("data")
                .size(),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "101", "-1", "abc", "1.5", ""])
    fun `a bad limit gives 400 with param limit`(limit: String) {
        get("$TEAMS?limit=$limit").error(400, "invalid_request", "limit")
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-a-uuid", ""])
    fun `a bad after gives 400 with param after`(after: String) {
        get("$TEAMS?after=$after").error(400, "invalid_request", "after")
        get("$TEAMS/${team()}/keys?after=$after").error(400, "invalid_request", "after")
    }

    @Test
    fun `POST keys answers 201 with the key, shown this once, and no digest`() {
        val team = team()
        val name = uniqueName()

        val result = post("$TEAMS/$team/keys", """{"name":"$name"}""").result(201)

        val key = result.tree()
        assertEquals(
            setOf("id", "team_id", "name", "key", "key_hint", "created_at", "expires_at", "revoked_at"),
            key.propertyNames().toSet(),
        )
        val plain = key.get("key").stringValue()
        assertTrue(KEY_FORMAT.matches(plain)) { "the key shape is wrong" }
        assertEquals(plain.take(10), key.get("key_hint").stringValue())
        assertEquals(team, key.get("team_id").stringValue())
        assertEquals(name, key.get("name").stringValue())
        assertTrue(key.get("expires_at").isNull)
        assertTrue(key.get("revoked_at").isNull)
        assertEquals("no-store", result.responseHeaders.cacheControl)
    }

    @Test
    fun `a created key calls v1`() {
        val key = key(team()).get("key").stringValue()

        chat(key).expectStatus().isOk
    }

    @Test
    fun `an expires_at in the future is kept, in any offset, and shown in UTC`() {
        val key = post("$TEAMS/${team()}/keys", """{"name":"a","expires_at":"2999-01-01T02:00:00+02:00"}""").json(201)

        assertEquals("2999-01-01T00:00:00Z", key.get("expires_at").stringValue())
    }

    @Test
    fun `a null expires_at is no expiry`() {
        val key = post("$TEAMS/${team()}/keys", """{"name":"a","expires_at":null}""").json(201)

        assertTrue(key.get("expires_at").isNull)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """"2020-01-01T00:00:00Z"""",
            """"tomorrow"""",
            """"2999-01-01"""",
            """"2999-01-01T00:00Z"""",
            """"2999-01-01T00:00:00"""",
            "1893456000",
            "true",
        ],
    )
    fun `an expires_at that is past or not RFC 3339 gives 400 with param expires_at`(value: String) {
        post("$TEAMS/${team()}/keys", """{"name":"a","expires_at":$value}""")
            .error(400, "invalid_request", "expires_at")
    }

    @Test
    fun `a typo of expires_at gives 400, or the key would never expire`() {
        post("$TEAMS/${team()}/keys", """{"name":"a","expire_at":"2999-01-01T00:00:00Z"}""")
            .error(400, "invalid_request")
    }

    @Test
    fun `a key for an unknown team, or one that is not a UUID, gives 404`() {
        post("$TEAMS/$UNKNOWN/keys", """{"name":"a"}""").error(404, "team_not_found")
        post("$TEAMS/not-a-uuid/keys", """{"name":"a"}""").error(404, "team_not_found")
    }

    @Test
    fun `a key name in use by an unrevoked key gives 409`() {
        val team = team()
        key(team, "ci")

        post("$TEAMS/$team/keys", """{"name":"ci"}""").error(409, "key_name_taken", "name")
    }

    @Test
    fun `GET keys lists revoked keys and no plain value or digest`() {
        val team = team()
        val first = key(team)
        val second = key(team)
        post("$TEAMS/$team/keys/${first.get("id").stringValue()}/revoke", null).result(200)

        val result = get("$TEAMS/$team/keys").result(200)

        val keys =
            result
                .tree()
                .get("data")
                .values()
                .toList()
        assertEquals(2, keys.size)
        keys.forEach { key ->
            assertEquals(
                setOf("id", "team_id", "name", "key_hint", "created_at", "expires_at", "revoked_at"),
                key.propertyNames().toSet(),
            )
        }
        assertFalse(keys[0].get("revoked_at").isNull)
        assertFalse(first.get("key").stringValue() in result.text())
        assertFalse(second.get("key").stringValue() in result.text())
    }

    @Test
    fun `GET keys pages, with has_more`() {
        val team = team()
        val keys = List(3) { key(team).get("id").stringValue() }

        val first = get("$TEAMS/$team/keys?limit=2").json(200)
        val second = get("$TEAMS/$team/keys?limit=2&after=${first.get("last_id").stringValue()}").json(200)

        assertEquals(keys.take(2), first.get("data").values().map { it.get("id").stringValue() })
        assertTrue(first.get("has_more").booleanValue())
        assertEquals(keys.drop(2), second.get("data").values().map { it.get("id").stringValue() })
        assertFalse(second.get("has_more").booleanValue())
    }

    @Test
    fun `GET keys of an unknown team gives 404, and of an empty one an empty list`() {
        get("$TEAMS/$UNKNOWN/keys").error(404, "team_not_found")
        get("$TEAMS/not-a-uuid/keys").error(404, "team_not_found")

        assertTrue(
            get("$TEAMS/${team()}/keys")
                .json(200)
                .get("data")
                .isEmpty,
        )
    }

    @Test
    fun `revoke answers 200, the key stops, and a second revoke keeps the first revoked_at`() {
        val team = team()
        val key = key(team)
        val plain = key.get("key").stringValue()
        chat(plain).expectStatus().isOk
        val route = "$TEAMS/$team/keys/${key.get("id").stringValue()}/revoke"

        val result = post(route, null).result(200)
        val first = result.tree().get("revoked_at").stringValue()
        val second = post(route, null).json(200).get("revoked_at").stringValue()

        assertFalse(plain in result.text())
        assertEquals(first, second)
        assertNotEquals("null", first)
        chat(plain).expectStatus().isUnauthorized
    }

    @Test
    fun `a revoked key's name is free again`() {
        val team = team()
        val key = key(team, "ci")
        post("$TEAMS/$team/keys/${key.get("id").stringValue()}/revoke", null).result(200)

        key(team, "ci")
    }

    @Test
    fun `revoking an unknown key, or one that is not a UUID, or another team's, gives 404 key_not_found`() {
        val team = team()
        val other = key(team())

        post("$TEAMS/$team/keys/$UNKNOWN/revoke", null).error(404, "key_not_found")
        post("$TEAMS/$team/keys/not-a-uuid/revoke", null).error(404, "key_not_found")
        post("$TEAMS/$team/keys/${other.get("id").stringValue()}/revoke", null).error(404, "key_not_found")
        post("$TEAMS/not-a-uuid/keys/$UNKNOWN/revoke", null).error(404, "key_not_found")
    }

    @ParameterizedTest
    @ValueSource(strings = ["PUT", "DELETE", "PATCH"])
    fun `a wrong method gives 405 with allow`(method: String) {
        admin
            .method(HttpMethod.valueOf(method))
            .uri(TEAMS)
            .exchange()
            .expectStatus()
            .isEqualTo(405)
            .expectHeader()
            .valueEquals("Allow", "GET, POST")
    }

    @Test
    fun `a wrong method on the other two routes gives 405 too`() {
        admin
            .delete()
            .uri("$TEAMS/$UNKNOWN/keys")
            .exchange()
            .expectStatus()
            .isEqualTo(405)
        admin
            .get()
            .uri("$TEAMS/$UNKNOWN/keys/$UNKNOWN/revoke")
            .exchange()
            .expectStatus()
            .isEqualTo(405)
    }

    @Test
    fun `no key gives 401, with no-store too`() {
        val result =
            raw
                .get()
                .uri(TEAMS)
                .exchange()
                .result(401)

        assertEquals("no-store", result.responseHeaders.cacheControl)
    }

    @Test
    fun `an unknown admin path with the key is 404 and no-store`() {
        val result = get("/admin/v1/nope").result(404)

        assertEquals("no-store", result.responseHeaders.cacheControl)
    }

    private companion object {
        const val TEAMS = "/admin/v1/teams"
        const val UNKNOWN = "01900000-0000-7000-8000-0000000000ff"

        // The version digit in the canonical 8-4-4-4-12 text.
        const val VERSION_INDEX = 14
        val KEY_FORMAT = Regex("llmgw_[A-Za-z0-9_-]{43}")
        val NAMES = AtomicInteger()
    }
}
