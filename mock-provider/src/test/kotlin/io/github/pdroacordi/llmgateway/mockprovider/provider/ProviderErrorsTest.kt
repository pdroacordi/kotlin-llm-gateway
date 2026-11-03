package io.github.pdroacordi.llmgateway.mockprovider.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

// One case per row of the FND-03 section 4 defaults table.
class ProviderErrorsTest {
    @ParameterizedTest(name = "{0}: OpenAI {1}/{2}, Anthropic {3}")
    @CsvSource(
        nullValues = ["null"],
        value = [
            "400, invalid_request_error, null, invalid_request_error",
            "401, invalid_request_error, invalid_api_key, authentication_error",
            "402, invalid_request_error, null, billing_error",
            "403, invalid_request_error, null, permission_error",
            "404, invalid_request_error, model_not_found, not_found_error",
            "409, invalid_request_error, null, conflict_error",
            "413, invalid_request_error, null, request_too_large",
            "429, requests, rate_limit_exceeded, rate_limit_error",
            "500, server_error, null, api_error",
            "503, service_unavailable_error, server_is_overloaded, api_error",
            "504, server_error, null, timeout_error",
            "529, server_error, null, overloaded_error",
            "418, invalid_request_error, null, invalid_request_error",
            "502, server_error, null, api_error",
        ],
    )
    fun `defaults per status`(
        status: Int,
        openAiType: String,
        openAiCode: String?,
        anthropicType: String,
    ) {
        val openAi = ProviderErrors.defaults(ProviderApi.OPENAI, status)
        val anthropic = ProviderErrors.defaults(ProviderApi.ANTHROPIC, status)

        assertEquals(openAiType, openAi.type)
        assertEquals(openAiCode, openAi.code)
        assertEquals(anthropicType, anthropic.type)
        assertNull(anthropic.code)
        assertEquals("mock-provider injected HTTP $status.", openAi.message)
        assertEquals(openAi.message, anthropic.message)
    }

    @Test
    fun `the API is chosen by path`() {
        assertEquals(ProviderApi.ANTHROPIC, ProviderApi.forPath("/v1/messages"))
        assertEquals(ProviderApi.ANTHROPIC, ProviderApi.forPath("/v1/messages/count_tokens"))
        assertEquals(ProviderApi.OPENAI, ProviderApi.forPath("/v1/messagesx"))
        assertEquals(ProviderApi.OPENAI, ProviderApi.forPath("/v1/chat/completions"))
        assertEquals(ProviderApi.OPENAI, ProviderApi.forPath("/__mock/scenarios"))
    }
}
