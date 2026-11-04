package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.uuid.Uuid

class ChatCompletionServiceStreamTest {
    private class RecordingPort(
        private val answer: (ChatCall) -> Flow<ProviderStreamEvent>,
    ) : ChatModelPort {
        val calls = mutableListOf<ChatCall>()

        override suspend fun complete(call: ChatCall): ProviderResult = error("not used by a stream")

        override fun stream(call: ChatCall): Flow<ProviderStreamEvent> {
            calls += call
            return answer(call)
        }
    }

    private val summaries = mutableListOf<StreamSummary>()

    private fun service(port: ChatModelPort): ChatCompletionService =
        ChatCompletionService({ DEPLOYMENT.copy(model = it) }, port, UsageEstimator())

    private fun stream(port: ChatModelPort): Flow<ChatStreamEvent> =
        service(port).stream(REQUEST_ID, REQUEST) { summaries += it }

    @Test
    fun `the flow is cold, no port call before collection`() =
        runTest {
            val port = RecordingPort { flowOf(CHUNK, ProviderStreamEvent.Completed) }

            val events = stream(port)

            assertTrue(port.calls.isEmpty())
            events.collect()
            assertEquals(1, port.calls.size)
        }

    @Test
    fun `the resolved deployment, the request and its ID reach the port`() =
        runTest {
            val port = RecordingPort { flowOf(CHUNK, ProviderStreamEvent.Completed) }

            stream(port).collect()

            assertEquals(listOf(ChatCall(REQUEST_ID, DEPLOYMENT, REQUEST)), port.calls)
        }

    @Test
    fun `a completed stream gives its events and onEnd once`() =
        runTest {
            val port = RecordingPort { flowOf(CHUNK, ProviderStreamEvent.Completed) }

            val events = stream(port).toList()

            assertEquals(ChatStreamEvent.Started(DEPLOYMENT), events.first())
            assertTrue(events.last() is ChatStreamEvent.Completed)
            assertEquals(StreamOutcome.Completed, summaries.single().outcome)
        }

    @Test
    fun `a failed stream gives Failed and onEnd once`() =
        runTest {
            val port = RecordingPort { flowOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout)) }

            val events = stream(port).toList()

            assertEquals(listOf(ChatStreamEvent.Failed(ProviderFailure.UpstreamTimeout)), events)
            assertEquals(StreamOutcome.Failed(ProviderFailure.UpstreamTimeout), summaries.single().outcome)
        }

    @Test
    fun `a cancelled collector gives Cancelled, onEnd once, and the CancellationException propagates`() =
        runTest {
            val first = CompletableDeferred<Unit>()
            val port =
                RecordingPort {
                    flow {
                        emit(CHUNK)
                        awaitCancellation()
                    }
                }

            val collecting =
                async(start = CoroutineStart.UNDISPATCHED) {
                    stream(port).collect { if (it is ChatStreamEvent.Chunk) first.complete(Unit) }
                }
            first.await()
            collecting.cancel()

            assertThrows<CancellationException> { collecting.await() }
            assertEquals(StreamOutcome.Cancelled, summaries.single().outcome)
            assertTrue(summaries.single().started)
        }

    @Test
    fun `a collector that stops early gives one onEnd`() =
        runTest {
            val port = RecordingPort { flowOf(CHUNK, CHUNK, ProviderStreamEvent.Completed) }

            stream(port).first()

            assertEquals(1, summaries.size)
        }

    @Test
    fun `a thrown bug propagates with one onEnd and outcome Crashed`() =
        runTest {
            val port =
                RecordingPort {
                    flow {
                        emit(CHUNK)
                        error("a bug in the port")
                    }
                }

            val failure = assertThrows<IllegalStateException> { stream(port).collect() }

            assertEquals("a bug in the port", failure.message)
            assertEquals(StreamOutcome.Crashed, summaries.single().outcome)
        }

    @Test
    fun `a port that ends with no terminal event is a bug, not an empty success`() =
        runTest {
            val port = RecordingPort { flowOf(CHUNK) }

            assertThrows<IllegalStateException> { stream(port).collect() }
            assertEquals(StreamOutcome.Crashed, summaries.single().outcome)
        }

    private companion object {
        val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))
        val MODEL = ModelName("mock-chat")
        val DEPLOYMENT = Deployment(DeploymentId("openai"), ProviderId("openai"), MODEL)
        val REQUEST = ChatRequest(MODEL, listOf(ChatMessage(Role("user"), MessageContent.Text("Hi"))))
        val CHUNK =
            ProviderStreamEvent.Chunk(
                ChatChunk("chatcmpl-1", 1793750400, MODEL, listOf(ChunkChoice(0, ChunkDelta("Hi")))),
            )
    }
}
