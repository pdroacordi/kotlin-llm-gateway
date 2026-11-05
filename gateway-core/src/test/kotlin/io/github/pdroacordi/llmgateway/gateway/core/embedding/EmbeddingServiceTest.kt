package io.github.pdroacordi.llmgateway.gateway.core.embedding

import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.chat.UsageEstimator
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.uuid.Uuid

class EmbeddingServiceTest {
    private class RecordingPort(
        private val answer: suspend (EmbeddingCall) -> EmbeddingResult,
    ) : EmbeddingModelPort {
        val calls = mutableListOf<EmbeddingCall>()

        override suspend fun embed(call: EmbeddingCall): EmbeddingResult {
            calls += call
            return answer(call)
        }
    }

    private fun service(port: EmbeddingModelPort): EmbeddingService =
        EmbeddingService({ DEPLOYMENT.copy(model = it) }, port, UsageEstimator())

    @Test
    fun `the resolved deployment, the input, the extras and the IDs reach the port`() =
        runTest {
            val port = RecordingPort { EmbeddingResult.Embedded(LIST, PROVIDER_USAGE) }

            service(port).embed(REQUEST_ID, TestKeys.TEAM_ID, REQUEST)

            assertEquals(listOf(EmbeddingCall(REQUEST_ID, TestKeys.TEAM_ID, DEPLOYMENT, INPUT, EXTRAS)), port.calls)
        }

    @Test
    fun `a port too busy to answer is the outcome Busy`() =
        runTest {
            val port = RecordingPort { EmbeddingResult.Busy }

            assertEquals(EmbeddingOutcome.Busy, service(port).embed(REQUEST_ID, TestKeys.TEAM_ID, REQUEST))
        }

    @Test
    fun `the provider's usage is kept and not flagged`() =
        runTest {
            val port = RecordingPort { EmbeddingResult.Embedded(LIST, PROVIDER_USAGE) }

            val outcome = service(port).embed(REQUEST_ID, TestKeys.TEAM_ID, REQUEST)

            assertEquals(EmbeddingOutcome.Answered(DEPLOYMENT, LIST, PROVIDER_USAGE), outcome)
            assertFalse((outcome as EmbeddingOutcome.Answered).usage.estimated)
        }

    @Test
    fun `omitted usage is estimated from the input and flagged`() =
        runTest {
            val port = RecordingPort { EmbeddingResult.Embedded(LIST, null) }

            val outcome = service(port).embed(REQUEST_ID, TestKeys.TEAM_ID, REQUEST) as EmbeddingOutcome.Answered

            assertTrue(outcome.usage.estimated)
            assertEquals(TokenCount(2), outcome.usage.inputTokens)
        }

    @Test
    fun `a failure comes back as Rejected with the same value`() =
        runTest {
            val failure = ProviderFailure.RejectedByProvider(400, "bad_thing", "input")
            val port = RecordingPort { EmbeddingResult.Failed(failure) }

            assertEquals(EmbeddingOutcome.Rejected(failure), service(port).embed(REQUEST_ID, TestKeys.TEAM_ID, REQUEST))
        }

    @Test
    fun `cancelling the caller cancels the port call`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val port =
                RecordingPort {
                    entered.complete(Unit)
                    awaitCancellation()
                }

            val call =
                async(start = CoroutineStart.UNDISPATCHED) {
                    service(port).embed(REQUEST_ID, TestKeys.TEAM_ID, REQUEST)
                }
            entered.await()
            call.cancel()

            assertThrows<CancellationException> { call.await() }
        }

    private companion object {
        val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))
        val MODEL = ModelName("mock-embedding")
        val DEPLOYMENT = Deployment(DeploymentId("openai"), ProviderId("openai"), MODEL)
        val INPUT = EmbeddingInput.Text("12345")
        val EXTRAS = JsonValue.Object(mapOf("dimensions" to JsonValue.Number("3")))
        val REQUEST = EmbeddingRequest(MODEL, INPUT, EXTRAS)
        val LIST = EmbeddingList(MODEL, listOf(Embedding(0, RawJson("[0.5]".encodeToByteArray()))))
        val PROVIDER_USAGE = Usage(TokenCount(14), TokenCount(0))
    }
}
