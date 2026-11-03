package io.github.pdroacordi.llmgateway.mockprovider.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ArrivalWebFilterTest {
    @Test
    fun `a logged model name has its control characters escaped`() {
        assertEquals(
            "mock\\u000a2026-11-03 ERROR forged\\u0007",
            ArrivalWebFilter.logSafe("mock\n2026-11-03 ERROR forged\u0007"),
        )
        assertEquals("mock-chat é", ArrivalWebFilter.logSafe("mock-chat é"))
    }

    @Test
    fun `routes are classified by path`() {
        assertEquals("openai.chat", ArrivalWebFilter.routeOf("/v1/chat/completions"))
        assertEquals("anthropic.messages", ArrivalWebFilter.routeOf("/v1/messages"))
        assertEquals("unknown", ArrivalWebFilter.routeOf("/v1/messages/count_tokens"))
        assertEquals(false, ArrivalWebFilter.isCounted("/__mock/stats"))
        assertEquals(false, ArrivalWebFilter.isCounted("/actuator/health"))
        assertEquals(true, ArrivalWebFilter.isCounted("/__mockx"))
    }
}
