package io.github.pdroacordi.llmgateway.gateway.core.embedding

import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.uuid.Uuid

class EmbeddingTypesTest {
    private fun extras(name: String) = JsonValue.Object(mapOf(name to JsonValue.Text(CANARY)))

    private fun assertNoContent(value: Any) {
        val text = value.toString()
        assertFalse(CANARY in text) { text }
    }

    @Test
    fun `toString of each input form prints sizes only`() {
        assertEquals("EmbeddingInput.Text(length=13)", EmbeddingInput.Text(CANARY).toString())
        assertEquals("EmbeddingInput.Texts(items=1)", EmbeddingInput.Texts(listOf(CANARY)).toString())
        assertEquals("EmbeddingInput.TokenIds(items=1)", EmbeddingInput.TokenIds(longArrayOf(1)).toString())
        assertEquals(
            "EmbeddingInput.TokenIdLists(items=1)",
            EmbeddingInput.TokenIdLists(listOf(longArrayOf(1))).toString(),
        )
    }

    @Test
    fun `toString of each type holding an input or a vector prints no content`() {
        val input = EmbeddingInput.Text(CANARY)
        val embedding = Embedding(0, RawJson(CANARY.encodeToByteArray()), extras("x"))

        assertNoContent(EmbeddingRequest(MODEL, input, extras("user")))
        assertNoContent(EmbeddingCall(REQUEST_ID, TestKeys.TEAM_ID, DEPLOYMENT, input, extras("user")))
        assertNoContent(embedding)
        assertNoContent(EmbeddingList(MODEL, listOf(embedding), extras("x")))
        assertNoContent(EmbeddingResult.Embedded(EmbeddingList(MODEL, listOf(embedding)), null))
    }

    @Test
    fun `RawJson prints its size`() {
        assertEquals("RawJson(size=3)", RawJson("[1]".encodeToByteArray()).toString())
    }

    @Test
    fun `RawJson is equal by content`() {
        val value = RawJson("[1]".encodeToByteArray())

        assertEquals(value, RawJson("[1]".encodeToByteArray()))
        assertEquals(value.hashCode(), RawJson("[1]".encodeToByteArray()).hashCode())
        assertNotEquals(value, RawJson("[2]".encodeToByteArray()))
    }

    @ParameterizedTest
    @ValueSource(strings = ["model", "input"])
    fun `a request and a call refuse a typed member in extras`(member: String) {
        val input = EmbeddingInput.Text("a")

        assertThrows<IllegalArgumentException> { EmbeddingRequest(MODEL, input, extras(member)) }
        assertThrows<IllegalArgumentException> {
            EmbeddingCall(REQUEST_ID, TestKeys.TEAM_ID, DEPLOYMENT, input, extras(member))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["object", "index", "embedding"])
    fun `an item refuses a typed member in extras`(member: String) {
        assertThrows<IllegalArgumentException> { Embedding(0, RawJson(ByteArray(0)), extras(member)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["object", "data", "model", "usage", "llmgw"])
    fun `a list refuses a typed member in extras`(member: String) {
        assertThrows<IllegalArgumentException> { EmbeddingList(MODEL, emptyList(), extras(member)) }
    }

    private companion object {
        const val CANARY = "canary-secret"
        val MODEL = ModelName("m")
        val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))
        val DEPLOYMENT = Deployment(DeploymentId("openai"), ProviderId("openai"), MODEL)
    }
}
