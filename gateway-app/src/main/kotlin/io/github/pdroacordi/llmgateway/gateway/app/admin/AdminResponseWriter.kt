package io.github.pdroacordi.llmgateway.gateway.app.admin

import io.github.pdroacordi.llmgateway.gateway.core.team.CreatedKey
import io.github.pdroacordi.llmgateway.gateway.core.team.Page
import io.github.pdroacordi.llmgateway.gateway.core.team.Team
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamKey
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/** The shapes of GW-04 section 4. No digest ever; the plain key only through [createdKey]. */
internal class AdminResponseWriter(
    private val mapper: JsonMapper,
) {
    private val nodes = mapper.nodeFactory

    suspend fun ok(tree: ObjectNode): ServerResponse = write(HttpStatus.OK, tree)

    suspend fun created(tree: ObjectNode): ServerResponse = write(HttpStatus.CREATED, tree)

    // Bytes: JacksonJsonEncoder logs the value it encodes at DEBUG, and this one can hold a key
    // (docs/knowledge/gotchas/spring-jackson-encoder-logs-the-value-at-debug.md).
    private suspend fun write(
        status: HttpStatus,
        tree: ObjectNode,
    ): ServerResponse =
        ServerResponse
            .status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(mapper.writeValueAsBytes(tree))

    fun team(team: Team): ObjectNode =
        nodes
            .objectNode()
            .put("id", team.id.toString())
            .put("name", team.name.value)
            .put("created_at", team.createdAt.toString())

    fun key(key: TeamKey): ObjectNode = keyHead(key).also { keyTail(it, key) }

    fun createdKey(created: CreatedKey): ObjectNode =
        keyHead(created.key)
            .put("key", created.secret.reveal())
            .also { keyTail(it, created.key) }

    private fun keyHead(key: TeamKey): ObjectNode =
        nodes
            .objectNode()
            .put("id", key.id.toString())
            .put("team_id", key.team.toString())
            .put("name", key.name.value)

    private fun keyTail(
        tree: ObjectNode,
        key: TeamKey,
    ) {
        tree
            .put("key_hint", key.hint)
            .put("created_at", key.createdAt.toString())
            .putTime("expires_at", key.expiresAt)
            .putTime("revoked_at", key.revokedAt)
    }

    private fun ObjectNode.putTime(
        name: String,
        time: Instant?,
    ): ObjectNode = if (time == null) putNull(name) else put(name, time.toString())

    fun <T> list(
        page: Page<T>,
        id: (T) -> String,
        item: (T) -> ObjectNode,
    ): ObjectNode {
        val tree = nodes.objectNode().put("object", "list")
        tree.putArray("data").addAll(page.items.map(item))
        return tree
            .putId("first_id", page.items.firstOrNull()?.let(id))
            .putId("last_id", page.items.lastOrNull()?.let(id))
            .put("has_more", page.hasMore)
    }

    private fun ObjectNode.putId(
        name: String,
        id: String?,
    ): ObjectNode = if (id == null) putNull(name) else put(name, id)
}
