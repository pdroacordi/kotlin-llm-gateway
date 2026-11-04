package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.chat.AssistantMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChoice
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChunk
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCompletion
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChunkChoice
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChunkDelta
import io.github.pdroacordi.llmgateway.gateway.core.chat.FinishReason
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderResult
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentLinkedQueue

/** Answers without a socket; [Wiring] puts it in front of the real adapter, which is never called. */
class FakeChatModelPort : ChatModelPort {
    val calls = ConcurrentLinkedQueue<ChatCall>()

    @Volatile
    var answer: (ChatCall) -> ProviderResult = { ProviderResult.Completed(completion(it), USAGE) }

    @Volatile
    var streamAnswer: (ChatCall) -> Flow<ProviderStreamEvent> = { stream() }

    override suspend fun complete(call: ChatCall): ProviderResult {
        calls += call
        return answer(call)
    }

    override fun stream(call: ChatCall): Flow<ProviderStreamEvent> {
        calls += call
        return streamAnswer(call)
    }

    fun reset() {
        calls.clear()
        answer = { ProviderResult.Completed(completion(it), USAGE) }
        streamAnswer = { stream() }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Wiring {
        @Bean
        @Primary
        fun fakeChatModelPort(): FakeChatModelPort = FakeChatModelPort()
    }

    companion object {
        val USAGE = Usage(TokenCount(14), TokenCount(2))

        fun completion(call: ChatCall): ChatCompletion =
            ChatCompletion(
                "chatcmpl-fake",
                1793750400,
                ModelName(call.deployment.model.value),
                listOf(ChatChoice(0, AssistantMessage("Hi there"), FinishReason("stop"))),
            )

        fun chunk(
            text: String,
            id: String = "chatcmpl-fake",
        ): ProviderStreamEvent.Chunk =
            ProviderStreamEvent.Chunk(
                ChatChunk(id, 1793750400, ModelName("mock-chat"), listOf(ChunkChoice(0, ChunkDelta(text)))),
            )

        val USAGE_CHUNK =
            ProviderStreamEvent.Chunk(
                ChatChunk("chatcmpl-fake", 1793750400, ModelName("mock-chat"), emptyList(), USAGE),
            )

        fun stream(): Flow<ProviderStreamEvent> =
            flowOf(chunk("Hi"), chunk(" there"), USAGE_CHUNK, ProviderStreamEvent.Completed)
    }
}
