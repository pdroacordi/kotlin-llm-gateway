package io.github.pdroacordi.llmgateway.mockprovider.sse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SseFramesTest {
    @Test
    fun `an OpenAI data event has a space after the colon and a blank line`() {
        assertEquals("data: {\"a\":1}\n\n", SseFrames.openAiData("""{"a":1}"""))
    }

    @Test
    fun `the OpenAI done sentinel`() {
        assertEquals("data: [DONE]\n\n", SseFrames.OPENAI_DONE)
    }

    @Test
    fun `an Anthropic event names its type`() {
        assertEquals(
            "event: ping\ndata: {\"type\":\"ping\"}\n\n",
            SseFrames.anthropicEvent("ping", """{"type":"ping"}"""),
        )
    }

    @Test
    fun `a newline in the JSON or the type is rejected, since it would split the event`() {
        assertThrows<IllegalArgumentException> { SseFrames.openAiData("{\n}") }
        assertThrows<IllegalArgumentException> { SseFrames.openAiData("{\r}") }
        assertThrows<IllegalArgumentException> { SseFrames.anthropicEvent("a\nb", "{}") }
    }
}
