package io.github.pdroacordi.llmgateway.gateway.app.error

import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.error.toReply
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import tools.jackson.databind.json.JsonMapper
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@ExtendWith(OutputCaptureExtension::class)
class StreamErrorFramesTest {
    private val mapper = JsonMapper.builder().build()
    private val frames = StreamErrorFrames(mapper)

    @ParameterizedTest
    @MethodSource("failures")
    fun `the frame holds what the mapper would write for the same failure`(failure: ProviderFailure) {
        val expected = mapper.writeValueAsString(OpenAiErrorEnvelope.of(failure.toReply(REQUEST_ID)))

        assertEquals("data: $expected\n\n", frames.frame(failure, REQUEST_ID).decodeToString())
    }

    @Test
    fun `a crash gives the internal_error frame and one ERROR line with a stack trace`(output: CapturedOutput) {
        val frame = frames.crash(IllegalStateException("canary-exception-message"), REQUEST_ID).decodeToString()

        val internal = mapper.writeValueAsString(OpenAiErrorEnvelope.of(GatewayError.Internal.toReply(REQUEST_ID)))
        assertEquals("data: $internal\n\n", frame)
        assertFalse("canary-exception-message" in frame) { frame }
        assertTrue("Stream crashed after commit" in output.all) { output.all }
        assertTrue("StreamErrorFramesTest" in output.all) { output.all }
    }

    private companion object {
        val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))

        @JvmStatic
        fun failures(): List<ProviderFailure> =
            listOf(
                ProviderFailure.ModelNotFound,
                ProviderFailure.RejectedByProvider(400, "context_length_exceeded", "messages[0].content"),
                ProviderFailure.RejectedByProvider(413, null, null),
                ProviderFailure.RejectedByProvider(422, "bad", null),
                ProviderFailure.UpstreamAuthFailed,
                ProviderFailure.UpstreamRateLimited(7.seconds),
                ProviderFailure.UpstreamRateLimited(null),
                ProviderFailure.UpstreamQuotaExhausted,
                ProviderFailure.ProviderUnavailable(7.seconds),
                ProviderFailure.ProviderUnavailable(null),
                ProviderFailure.UpstreamTimeout,
                ProviderFailure.UpstreamError,
            )
    }
}
