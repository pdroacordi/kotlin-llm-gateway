package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.app.error.StreamErrorFrames
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChunk
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChunkChoice
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChunkDelta
import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import kotlin.uuid.Uuid

class ChatStreamFramesTest {
    private val mapper = JsonMapper.builder().build()
    private val headers = HttpHeaders()

    private fun frames(
        events: Flow<ChatStreamEvent>,
        includeUsage: Boolean = false,
    ): Flow<ByteArray> =
        ChatStreamFrames(
            mapper,
            StreamErrorFrames(mapper),
            REQUEST_ID,
            includeUsage,
            headers,
            ChatStreamLog(Clock.systemUTC(), REQUEST_ID),
        ).frames(events)

    private suspend fun text(
        vararg events: ChatStreamEvent,
        includeUsage: Boolean = false,
    ): List<String> = frames(flowOf(*events), includeUsage).toList().map { it.decodeToString() }

    @Test
    fun `Started sets the four commit headers and writes no frame`() =
        runBlocking<Unit> {
            val frames = text(STARTED)

            assertTrue(frames.isEmpty())
            assertEquals("text/event-stream;charset=UTF-8", headers.getFirst("content-type"))
            assertEquals("no-cache", headers.getFirst("cache-control"))
            assertEquals("no", headers.getFirst("x-accel-buffering"))
            assertEquals("openai", headers.getFirst("x-gateway-deployment"))
            assertEquals(4, headers.size())
        }

    @Test
    fun `a Chunk gives the exact bytes of one frame`() =
        runBlocking<Unit> {
            assertEquals(
                listOf(
                    "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\"," +
                        "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"}}]}\n\n",
                ),
                text(STARTED, CHUNK),
            )
        }

    @Test
    fun `include_usage reaches the chunk writer`() =
        runBlocking<Unit> {
            assertTrue("\"usage\":null" in text(STARTED, CHUNK, includeUsage = true).single())
        }

    @Test
    fun `Completed gives DONE`() =
        runBlocking<Unit> {
            assertEquals("data: [DONE]\n\n", text(STARTED, CHUNK, COMPLETED).last())
        }

    @Test
    fun `Failed before Started is thrown, as a GatewayErrorException with no frame and no header`() =
        runBlocking<Unit> {
            val failed = ChatStreamEvent.Failed(ProviderFailure.UpstreamTimeout)

            val thrown = assertThrows<GatewayErrorException> { text(failed) }

            assertEquals(ProviderFailure.UpstreamTimeout, thrown.error)
            assertTrue(headers.isEmpty)
        }

    @Test
    fun `Failed after Started gives the envelope frame and the flow ends normally`() =
        runBlocking<Unit> {
            val frames = text(STARTED, CHUNK, ChatStreamEvent.Failed(ProviderFailure.UpstreamTimeout))

            assertEquals(2, frames.size)
            val json = frames.last().removePrefix("data: ").trimEnd()
            val error = mapper.readTree(json).get("error")
            assertEquals("upstream_timeout", error.get("code").stringValue())
            assertEquals(4, error.size())
        }

    @Test
    fun `a bug before Started propagates for the mapper`() =
        runBlocking<Unit> {
            val events = flow<ChatStreamEvent> { error("a bug") }

            val thrown = assertThrows<IllegalStateException> { frames(events).toList() }

            assertEquals("a bug", thrown.message)
        }

    @Test
    fun `a bug after Started gives the internal_error frame and ends normally`() =
        runBlocking<Unit> {
            val events =
                flow {
                    emit(STARTED)
                    emit(CHUNK)
                    error("a bug")
                }

            val frames = frames(events).toList().map { it.decodeToString() }

            assertEquals(2, frames.size)
            assertTrue("\"code\":\"internal_error\"" in frames.last()) { frames.last() }
            assertTrue(REQUEST_ID.toString() in frames.last()) { frames.last() }
        }

    @Test
    fun `a bug after DONE adds no frame`() =
        runBlocking<Unit> {
            val events =
                flow {
                    emit(STARTED)
                    emit(CHUNK)
                    emit(COMPLETED)
                    error("a bug in the drain")
                }

            val frames = frames(events).toList().map { it.decodeToString() }

            assertEquals("data: [DONE]\n\n", frames.last())
            assertEquals(2, frames.size)
        }

    @Test
    fun `a bug after an error frame adds no frame`() =
        runBlocking<Unit> {
            val events =
                flow {
                    emit(STARTED)
                    emit(ChatStreamEvent.Failed(ProviderFailure.UpstreamTimeout))
                    error("a bug after the failure")
                }

            val frames = frames(events).toList().map { it.decodeToString() }

            assertEquals(1, frames.size)
            assertTrue("upstream_timeout" in frames.single()) { frames.single() }
        }

    @Test
    fun `a CancellationException after Started is never caught`() =
        runBlocking<Unit> {
            val events =
                flow {
                    emit(STARTED)
                    emit(CHUNK)
                    throw CancellationException("gone")
                }

            assertThrows<CancellationException> { frames(events).toList() }
        }

    private companion object {
        val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))
        val MODEL = ModelName("m")
        val STARTED = ChatStreamEvent.Started(Deployment(DeploymentId("openai"), ProviderId("openai"), MODEL))
        val CHUNK = ChatStreamEvent.Chunk(ChatChunk("c", 1, MODEL, listOf(ChunkChoice(0, ChunkDelta("Hi")))))
        val COMPLETED = ChatStreamEvent.Completed(Usage(TokenCount(1), TokenCount(1)))
    }
}
