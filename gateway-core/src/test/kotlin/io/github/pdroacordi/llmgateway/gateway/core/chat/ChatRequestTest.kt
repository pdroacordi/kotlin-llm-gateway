package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.uuid.Uuid

class ChatRequestTest {
    @Test
    fun `a request with no messages is rejected`() {
        assertThrows<IllegalArgumentException> { ChatRequest(ModelName("m"), emptyList()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["model", "messages", "stream"])
    fun `a typed member cannot also sit in extras`(key: String) {
        val extras = JsonValue.Object(mapOf(key to JsonValue.Null))

        assertThrows<IllegalArgumentException> { ChatRequest(ModelName("m"), listOf(USER), extras) }
    }

    @Test
    fun `any other member is kept in extras`() {
        val extras = JsonValue.Object(mapOf("seed" to JsonValue.Number("9007199254740993")))

        assertEquals(extras, ChatRequest(ModelName("m"), listOf(USER), extras).extras)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\t"])
    fun `a model name and a role cannot be blank`(value: String) {
        assertThrows<IllegalArgumentException> { ModelName(value) }
        assertThrows<IllegalArgumentException> { Role(value) }
    }

    @Test
    fun `a role is any non-blank text, so a role the gateway never heard of passes`() {
        assertEquals("developer-2030", Role("developer-2030").value)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("textCarriers")
    fun `toString prints no prompt or completion text`(carrier: Any) {
        assertFalse(CANARY in carrier.toString()) { carrier.toString() }
    }

    companion object {
        private const val CANARY = "canary-text"
        private val USER = ChatMessage(Role("user"), MessageContent.Text("Hi"))
        private val SECRET_EXTRAS = JsonValue.Object(mapOf("note" to JsonValue.Text(CANARY)))
        private val ASSISTANT = AssistantMessage(CANARY, SECRET_EXTRAS)
        private val CHOICE = ChatChoice(0, ASSISTANT, FinishReason("stop"), SECRET_EXTRAS)
        private val COMPLETION = ChatCompletion("id", 1, ModelName("m"), listOf(CHOICE), SECRET_EXTRAS)
        private val USAGE = Usage(TokenCount(1), TokenCount(2), extras = SECRET_EXTRAS)
        private val DEPLOYMENT = Deployment(DeploymentId("d"), ProviderId("p"), ModelName("m"))
        private val CALL =
            ChatCall(
                RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057")),
                DEPLOYMENT,
                ChatRequest(
                    ModelName("m"),
                    listOf(ChatMessage(Role("user"), MessageContent.Text(CANARY))),
                    SECRET_EXTRAS,
                ),
            )

        @JvmStatic
        fun textCarriers(): List<Arguments> =
            listOf(
                named(
                    "ChatRequest",
                    ChatRequest(
                        ModelName("m"),
                        listOf(ChatMessage(Role("user"), MessageContent.Text(CANARY))),
                        SECRET_EXTRAS,
                    ),
                ),
                named("ChatMessage", ChatMessage(Role(CANARY), MessageContent.Text(CANARY), SECRET_EXTRAS)),
                named("MessageContent.Text", MessageContent.Text(CANARY)),
                named(
                    "MessageContent.Parts",
                    MessageContent.Parts(listOf(ContentPart.Text(CANARY), ContentPart.Opaque(JsonValue.Text(CANARY)))),
                ),
                named("ContentPart.Text", ContentPart.Text(CANARY, SECRET_EXTRAS)),
                named("ContentPart.Opaque", ContentPart.Opaque(JsonValue.Text(CANARY))),
                named("ChatCompletion", ChatCompletion("id", 1, ModelName("m"), listOf(CHOICE), SECRET_EXTRAS)),
                named("ChatChoice", CHOICE),
                named("AssistantMessage", ASSISTANT),
                named("JsonValue.Text", JsonValue.Text(CANARY)),
                named("JsonValue.Array", JsonValue.Array(listOf(JsonValue.Text(CANARY)))),
                named("JsonValue.Object", SECRET_EXTRAS),
                named("Usage", USAGE),
                named("ChatCall", CALL),
                named("ProviderResult.Completed", ProviderResult.Completed(COMPLETION, USAGE)),
                named("ChatOutcome.Answered", ChatOutcome.Answered(DEPLOYMENT, COMPLETION, USAGE)),
            ).map { Arguments.of(it) }
    }
}
