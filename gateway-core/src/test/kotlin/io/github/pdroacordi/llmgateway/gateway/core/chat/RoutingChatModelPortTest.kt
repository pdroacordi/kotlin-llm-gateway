package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.uuid.Uuid

class RoutingChatModelPortTest {
    private val openai = mockk<ChatModelPort>()
    private val anthropic = mockk<ChatModelPort>()
    private val port = RoutingChatModelPort(mapOf(OPENAI to openai, ANTHROPIC to anthropic))

    private fun call(provider: ProviderId): ChatCall =
        ChatCall(
            RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057")),
            Deployment(DeploymentId(provider.value), provider, ModelName("m")),
            ChatRequest(ModelName("m"), listOf(ChatMessage(Role("user"), MessageContent.Text("Hi")))),
        )

    @Test
    fun `complete goes to the deployment's provider only`() =
        runTest {
            val result = ProviderResult.Failed(ProviderFailure.UpstreamError)
            coEvery { anthropic.complete(any()) } returns result

            assertEquals(result, port.complete(call(ANTHROPIC)))
            coVerify(exactly = 0) { openai.complete(any()) }
        }

    @Test
    fun `stream goes to the deployment's provider`() =
        runTest {
            every { anthropic.stream(any()) } returns flowOf(ProviderStreamEvent.Completed)

            assertEquals(listOf(ProviderStreamEvent.Completed), port.stream(call(ANTHROPIC)).toList())
        }

    @Test
    fun `a provider with no adapter is ModelNotFound on both methods`() =
        runTest {
            val unknown = call(ProviderId("none"))

            assertEquals(ProviderResult.Failed(ProviderFailure.ModelNotFound), port.complete(unknown))
            assertEquals(
                listOf(ProviderStreamEvent.Failed(ProviderFailure.ModelNotFound)),
                port.stream(unknown).toList(),
            )
        }

    @Test
    fun `a cancellation passes through on both methods`() =
        runTest {
            coEvery { openai.complete(any()) } throws CancellationException("gone")
            every { openai.stream(any()) } returns flow { throw CancellationException("gone") }

            assertThrows<CancellationException> { port.complete(call(OPENAI)) }
            assertThrows<CancellationException> { port.stream(call(OPENAI)).toList() }
        }

    private companion object {
        val OPENAI = ProviderId("openai")
        val ANTHROPIC = ProviderId("anthropic")
    }
}
