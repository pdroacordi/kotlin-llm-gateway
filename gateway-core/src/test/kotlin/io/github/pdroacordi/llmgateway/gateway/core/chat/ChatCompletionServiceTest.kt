package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.uuid.Uuid

class ChatCompletionServiceTest {
    private class RecordingPort(
        private val answer: suspend (ChatCall) -> ProviderResult,
    ) : ChatModelPort {
        val calls = mutableListOf<ChatCall>()

        override suspend fun complete(call: ChatCall): ProviderResult {
            calls += call
            return answer(call)
        }
    }

    private fun service(port: ChatModelPort): ChatCompletionService =
        ChatCompletionService({ DEPLOYMENT.copy(model = it) }, port, UsageEstimator())

    @Test
    fun `the resolved deployment, the request and its ID reach the port`() =
        runTest {
            val port = RecordingPort { ProviderResult.Completed(COMPLETION, PROVIDER_USAGE) }

            service(port).complete(REQUEST_ID, REQUEST)

            assertEquals(listOf(ChatCall(REQUEST_ID, DEPLOYMENT, REQUEST)), port.calls)
        }

    @Test
    fun `the provider's usage is kept and not flagged`() =
        runTest {
            val port = RecordingPort { ProviderResult.Completed(COMPLETION, PROVIDER_USAGE) }

            val outcome = service(port).complete(REQUEST_ID, REQUEST)

            assertEquals(ChatOutcome.Answered(DEPLOYMENT, COMPLETION, PROVIDER_USAGE), outcome)
            assertFalse((outcome as ChatOutcome.Answered).usage.estimated)
        }

    @Test
    fun `omitted usage is estimated and flagged`() =
        runTest {
            val port = RecordingPort { ProviderResult.Completed(COMPLETION, null) }

            val outcome = service(port).complete(REQUEST_ID, REQUEST) as ChatOutcome.Answered

            assertTrue(outcome.usage.estimated)
            assertEquals(UsageEstimator().estimate(REQUEST, COMPLETION), outcome.usage)
            assertNull(outcome.usage.cachedInputTokens)
        }

    @Test
    fun `a failure comes back as Rejected with the same value`() =
        runTest {
            val failure = ProviderFailure.RejectedByProvider(400, "bad_thing", "seed")
            val port = RecordingPort { ProviderResult.Failed(failure) }

            assertEquals(ChatOutcome.Rejected(failure), service(port).complete(REQUEST_ID, REQUEST))
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

            val call = async(start = CoroutineStart.UNDISPATCHED) { service(port).complete(REQUEST_ID, REQUEST) }
            entered.await()
            call.cancel()

            assertThrows<CancellationException> { call.await() }
        }

    private companion object {
        val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))
        val DEPLOYMENT = Deployment(DeploymentId("openai"), ProviderId("openai"), ModelName("mock-chat"))
        val REQUEST =
            ChatRequest(ModelName("mock-chat"), listOf(ChatMessage(Role("user"), MessageContent.Text("Hi"))))
        val COMPLETION =
            ChatCompletion(
                "chatcmpl-1",
                1793750400,
                ModelName("mock-chat"),
                listOf(ChatChoice(0, AssistantMessage("Hi there"), FinishReason("stop"))),
            )
        val PROVIDER_USAGE = Usage(TokenCount(14), TokenCount(2), TokenCount(0))
    }
}
