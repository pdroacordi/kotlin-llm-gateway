package io.github.pdroacordi.llmgateway.gateway.core.embedding

import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.uuid.Uuid

class RoutingEmbeddingModelPortTest {
    private val openai = mockk<EmbeddingModelPort>()
    private val other = mockk<EmbeddingModelPort>()
    private val port = RoutingEmbeddingModelPort(mapOf(OPENAI to openai, OTHER to other))

    private fun call(provider: ProviderId): EmbeddingCall =
        EmbeddingCall(
            RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057")),
            TestKeys.TEAM_ID,
            Deployment(DeploymentId(provider.value), provider, ModelName("m")),
            EmbeddingInput.Text("a"),
        )

    @Test
    fun `a call goes to the deployment's provider only`() =
        runTest {
            val result = EmbeddingResult.Failed(ProviderFailure.UpstreamError)
            coEvery { other.embed(any()) } returns result

            assertEquals(result, port.embed(call(OTHER)))
            coVerify(exactly = 0) { openai.embed(any()) }
        }

    @Test
    fun `a provider with no port is the 400 for the model, not model_not_found`() =
        runTest {
            assertEquals(UNSUPPORTED, port.embed(call(ProviderId("anthropic"))))
        }

    @Test
    fun `an empty map is valid and every call is that 400`() =
        runTest {
            assertEquals(UNSUPPORTED, RoutingEmbeddingModelPort(emptyMap()).embed(call(OPENAI)))
        }

    @Test
    fun `a cancellation passes through`() =
        runTest {
            coEvery { openai.embed(any()) } throws CancellationException("gone")

            assertThrows<CancellationException> { port.embed(call(OPENAI)) }
        }

    private companion object {
        val OPENAI = ProviderId("openai")
        val OTHER = ProviderId("other")
        val UNSUPPORTED =
            EmbeddingResult.Failed(ProviderFailure.UnsupportedByProvider("unsupported_value", "model"))
    }
}
