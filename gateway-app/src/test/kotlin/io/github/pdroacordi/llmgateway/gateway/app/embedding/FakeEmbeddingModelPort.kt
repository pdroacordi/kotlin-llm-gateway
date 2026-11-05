package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.embedding.Embedding
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingCall
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingList
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingModelPort
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingResult
import io.github.pdroacordi.llmgateway.gateway.core.embedding.RawJson
import kotlinx.coroutines.CompletableDeferred
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentLinkedQueue

/** Answers without a socket; [Wiring] puts it in front of the real adapter, which is never called. */
class FakeEmbeddingModelPort : EmbeddingModelPort {
    val calls = ConcurrentLinkedQueue<EmbeddingCall>()

    @Volatile
    var answer: (EmbeddingCall) -> EmbeddingResult = { EmbeddingResult.Embedded(list(it), USAGE) }

    /** A call waits here once counted, until the test completes it or the call is cancelled. */
    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun embed(call: EmbeddingCall): EmbeddingResult {
        calls += call
        gate?.await()
        return answer(call)
    }

    fun reset() {
        calls.clear()
        gate = null
        answer = { EmbeddingResult.Embedded(list(it), USAGE) }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Wiring {
        @Bean
        @Primary
        fun fakeEmbeddingModelPort(): FakeEmbeddingModelPort = FakeEmbeddingModelPort()
    }

    companion object {
        const val VECTOR = "[ 1e-5, 0.10 ]"
        val USAGE = Usage(TokenCount(2), TokenCount(0))

        fun list(call: EmbeddingCall): EmbeddingList =
            EmbeddingList(
                ModelName(call.deployment.model.value),
                listOf(Embedding(0, RawJson(VECTOR.encodeToByteArray()))),
            )
    }
}
