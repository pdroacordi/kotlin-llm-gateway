package io.github.pdroacordi.llmgateway.mockprovider

import com.jayway.jsonpath.JsonPath
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient

/** Request helpers shared by the integration tests. The keys are fake test values; only fingerprints are stored. */
object MockHttp {
    const val KEY = "sk-test-integration"

    fun WebTestClient.postJson(
        path: String,
        body: String,
        headers: Map<String, String> = mapOf("authorization" to "Bearer $KEY"),
    ): EntityExchangeResult<ByteArray> =
        post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { target -> headers.forEach { (name, value) -> target.set(name, value) } }
            .bodyValue(body)
            .exchange()
            .expectBody()
            .returnResult()

    fun WebTestClient.chat(
        body: String,
        headers: Map<String, String> = mapOf("authorization" to "Bearer $KEY"),
    ): EntityExchangeResult<ByteArray> = postJson("/v1/chat/completions", body, headers)

    fun WebTestClient.messages(
        body: String,
        headers: Map<String, String> = mapOf("x-api-key" to KEY, "anthropic-version" to "2023-06-01"),
    ): EntityExchangeResult<ByteArray> = postJson("/v1/messages", body, headers)

    fun WebTestClient.putScenario(
        model: String,
        scenario: String,
    ) {
        put()
            .uri("/__mock/scenarios/{model}", model)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(scenario)
            .exchange()
            .expectStatus()
            .isNoContent
    }

    fun WebTestClient.reset() {
        post()
            .uri("/__mock/reset")
            .exchange()
            .expectStatus()
            .isNoContent
    }

    fun WebTestClient.getJson(path: String): String =
        get()
            .uri(path)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .returnResult()
            .bodyText()

    fun EntityExchangeResult<ByteArray>.bodyText(): String = responseBody?.decodeToString().orEmpty()

    fun <T> EntityExchangeResult<ByteArray>.json(path: String): T = JsonPath.parse(bodyText()).read(path)

    fun <T> String.json(path: String): T = JsonPath.parse(this).read(path)

    fun chatBody(
        content: String,
        model: String = "mock-chat",
        extra: String = "",
    ): String = """{"model":"$model"$extra,"messages":[{"role":"user","content":"$content"}]}"""

    fun messagesBody(
        content: String,
        model: String = "mock-claude",
        extra: String = "",
    ): String = """{"model":"$model","max_tokens":256$extra,"messages":[{"role":"user","content":"$content"}]}"""
}
