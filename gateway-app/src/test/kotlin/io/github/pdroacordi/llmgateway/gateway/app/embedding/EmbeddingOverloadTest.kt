package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingCpuPort
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.RejectedExecutionException

/** A lane that refuses work, and how a refusal reads in the log (ADR-GW-05-011, ADR-GW-05-012). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeEmbeddingModelPort.Wiring::class, EmbeddingOverloadTest.Wiring::class)
@ExtendWith(OutputCaptureExtension::class)
@Timeout(60)
@TestPropertySource(
    properties = [
        "llmgw.providers.openai.embeddings.max-response-size=64KB",
        "llmgw.providers.openai.embeddings.max-in-flight-bytes=64KB",
    ],
)
class EmbeddingOverloadTest(
    @LocalServerPort private val port: Int,
    @Autowired private val lane: RefusingLane,
) {
    /** The real lane until [refusing]; a closed lane and a full one both surface as this exception. */
    class RefusingLane(
        private val real: EmbeddingCpuPort,
    ) : EmbeddingCpuPort {
        @Volatile
        var refusing = false

        override suspend fun <T : Any> run(work: () -> T?): T? {
            if (refusing) throw RejectedExecutionException("refused")
            return real.run(work)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Wiring {
        @Bean
        @Primary
        fun refusingLane(
            @Qualifier("embeddingCpuPort") real: EmbeddingCpuPort,
        ): RefusingLane = RefusingLane(real)
    }

    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun useTheRealLane() {
        lane.refusing = false
    }

    private fun post(body: String): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/v1/embeddings"))
                .header("Authorization", TestKeys.BEARER)
                .header("Content-Type", "application/json")
                .timeout(CALL_LIMIT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `a lane that refuses the parse gives 503 gateway_overloaded, not 500`() {
        lane.refusing = true

        val response = post("""{"model":"mock-embedding","input":"a"}""")

        assertEquals(503, response.statusCode()) { response.body() }
        assertTrue("gateway_overloaded" in response.body()) { response.body() }
        assertTrue(response.headers().firstValue("retry-after").isPresent)
    }

    @Test
    fun `a run of refusals writes no ERROR and no stack trace, and at most one line at WARN`(output: CapturedOutput) {
        val mark = output.all.length

        repeat(REFUSALS) {
            assertEquals(503, post("{" + "x".repeat(OVER_BUDGET_BODY)).statusCode())
        }

        val log = output.all.substring(mark)
        assertFalse(" ERROR " in log) { log.take(LOG_HEAD) }
        assertFalse("\tat " in log) { log.take(LOG_HEAD) }
        val warnings = log.lines().count { " WARN " in it && "gateway_overloaded" in it }
        assertTrue(warnings <= 1) { log.take(LOG_HEAD) }
    }

    private companion object {
        const val REFUSALS = 5
        const val OVER_BUDGET_BODY = 40 * 1024
        const val LOG_HEAD = 2000
        val CALL_LIMIT: Duration = Duration.ofSeconds(10)
    }
}
