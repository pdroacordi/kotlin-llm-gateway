package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.netty.util.ResourceLeakDetector
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * The adapter's copy-and-release under a leak detector (ADR-GW-02-008, ADR-GW-02-012). The detector reports on a later
 * allocation, after GC, so it can miss a leak and never invents one: hence the volume and the deliberate violation
 * recorded in the GW-02 log.
 */
@ExtendWith(OutputCaptureExtension::class)
class StreamBufferLeakIntegrationTest : AnthropicBackedTest() {
    private val client by lazy {
        WebClient
            .builder()
            .baseUrl("http://127.0.0.1:$port")
            .defaultHeader("Authorization", TestKeys.BEARER)
            .build()
    }

    private fun stream(
        frames: Long,
        body: String = STREAM,
    ): Flux<ByteArray> =
        client
            .post()
            .uri(ROUTE)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body.encodeToByteArray())
            .exchangeToFlux { it.bodyToFlux(ByteArray::class.java).take(frames) }

    @Test
    fun `500 streams cancelled mid-flight leak no pooled buffer`(output: CapturedOutput) {
        val words = "word ".repeat(WORDS)
        scenario("""{"otherwise":{"latency":{"perTokenMs":5},"content":{"mode":"fixed","text":"$words"}}}""")

        val cancelled =
            Flux
                .range(0, CANCELLED)
                .flatMap({ stream(FRAMES).collectList() }, CONCURRENCY)
                .collectList()
                .block(BLOCK_LIMIT)
                .orEmpty()

        assertEquals(CANCELLED, cancelled.size)
        repeat(ROUNDS) {
            System.gc()
            // ResourceLeakDetector drains its queue on the next tracked allocation.
            Flux
                .range(0, CONCURRENCY)
                .flatMap({ stream(FRAMES).collectList() }, CONCURRENCY)
                .blockLast(BLOCK_LIMIT)
        }
        assertFalse("LEAK:" in output.all) { output.all.lines().first { "LEAK:" in it } }
    }

    @Test
    fun `500 Anthropic streams cancelled mid-flight leak no pooled buffer`(output: CapturedOutput) {
        val words = "word ".repeat(WORDS)
        claudeScenario("""{"otherwise":{"latency":{"perTokenMs":5},"content":{"mode":"fixed","text":"$words"}}}""")

        val cancelled =
            Flux
                .range(0, CANCELLED)
                .flatMap({ stream(FRAMES, CLAUDE_STREAM).collectList() }, CONCURRENCY)
                .collectList()
                .block(BLOCK_LIMIT)
                .orEmpty()

        assertEquals(CANCELLED, cancelled.size)
        repeat(ROUNDS) {
            System.gc()
            // ResourceLeakDetector drains its queue on the next tracked allocation.
            Flux
                .range(0, CONCURRENCY)
                .flatMap({ stream(FRAMES, CLAUDE_STREAM).collectList() }, CONCURRENCY)
                .blockLast(BLOCK_LIMIT)
        }
        assertFalse("LEAK:" in output.all) { output.all.lines().first { "LEAK:" in it } }
    }

    companion object {
        private const val CANCELLED = 500
        private const val CONCURRENCY = 16
        private const val FRAMES = 3L
        private const val WORDS = 200
        private const val ROUNDS = 5
        private val BLOCK_LIMIT: Duration = Duration.ofMinutes(2)
        private val before: ResourceLeakDetector.Level = ResourceLeakDetector.getLevel()

        @JvmStatic
        @BeforeAll
        fun paranoid() {
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
        }

        @JvmStatic
        @AfterAll
        fun restore() {
            ResourceLeakDetector.setLevel(before)
        }
    }
}
