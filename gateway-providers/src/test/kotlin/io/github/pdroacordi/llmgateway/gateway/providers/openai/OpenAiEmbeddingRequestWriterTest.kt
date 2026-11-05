package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingCall
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingInput
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.uuid.Uuid

class OpenAiEmbeddingRequestWriterTest {
    private val mapper = JsonMapper.builder().build()

    private fun written(
        input: EmbeddingInput,
        extras: JsonValue.Object = JsonValue.Object.EMPTY,
    ): String {
        val call =
            EmbeddingCall(
                RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057")),
                TestKeys.TEAM_ID,
                Deployment(DeploymentId("openai"), ProviderId("openai"), ModelName("resolved")),
                input,
                extras,
            )
        return mapper.writeValueAsString(OpenAiEmbeddingRequestWriter.write(call, mapper.nodeFactory))
    }

    @Test
    fun `a text is a string, with the deployment's model`() {
        assertEquals("""{"model":"resolved","input":"a"}""", written(EmbeddingInput.Text("a")))
    }

    @Test
    fun `texts are an array of strings, and an empty list stays an array`() {
        assertEquals("""{"model":"resolved","input":["a","b"]}""", written(EmbeddingInput.Texts(listOf("a", "b"))))
        assertEquals("""{"model":"resolved","input":[]}""", written(EmbeddingInput.Texts(emptyList())))
    }

    @Test
    fun `token IDs are an array of integers, to the last digit of a Long`() {
        assertEquals(
            """{"model":"resolved","input":[1,9223372036854775807]}""",
            written(EmbeddingInput.TokenIds(longArrayOf(1, Long.MAX_VALUE))),
        )
    }

    @Test
    fun `token ID lists are nested arrays, and an empty one stays`() {
        assertEquals(
            """{"model":"resolved","input":[[1],[2,3],[]]}""",
            written(EmbeddingInput.TokenIdLists(listOf(longArrayOf(1), longArrayOf(2, 3), longArrayOf()))),
        )
    }

    @Test
    fun `extras follow in order, unknown members included`() {
        val extras =
            JsonValue.Object(
                linkedMapOf(
                    "x_future" to JsonValue.Array(listOf(JsonValue.Number("1"), JsonValue.Null)),
                    "encoding_format" to JsonValue.Text("base64"),
                    "dimensions" to JsonValue.Number("256"),
                    "user" to JsonValue.Text("u"),
                ),
            )

        assertEquals(
            """{"model":"resolved","input":"a","x_future":[1,null],"encoding_format":"base64",""" +
                """"dimensions":256,"user":"u"}""",
            written(EmbeddingInput.Text("a"), extras),
        )
    }

    @Test
    fun `llmgw reaches no provider`() {
        val extras =
            JsonValue.Object(linkedMapOf("llmgw" to JsonValue.Object.EMPTY, "dimensions" to JsonValue.Number("3")))

        assertEquals(
            """{"model":"resolved","input":"a","dimensions":3}""",
            written(EmbeddingInput.Text("a"), extras),
        )
    }

    @Test
    fun `an absent encoding_format stays absent`() {
        assertEquals("""{"model":"resolved","input":"a"}""", written(EmbeddingInput.Text("a")))
    }
}
