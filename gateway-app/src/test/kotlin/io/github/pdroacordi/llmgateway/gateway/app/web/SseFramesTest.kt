package io.github.pdroacordi.llmgateway.gateway.app.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SseFramesTest {
    @Test
    fun `a frame is exactly data, a space, the JSON and a blank line`() {
        assertEquals("data: {\"a\":1}\n\n", SseFrames.data("""{"a":1}""".encodeToByteArray()).decodeToString())
    }

    @Test
    fun `DONE is exactly the OpenAI sentinel`() {
        assertEquals("data: [DONE]\n\n", SseFrames.DONE.decodeToString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["a\nb", "a\rb", "a\r\nb", "a\n"])
    fun `a raw line break is rejected`(value: String) {
        assertThrows<IllegalArgumentException> { SseFrames.data(value.encodeToByteArray()) }
    }

    @Test
    fun `an escaped line break in JSON passes`() {
        assertEquals("data: \"a\\nb\"\n\n", SseFrames.data("\"a\\nb\"".encodeToByteArray()).decodeToString())
    }
}
