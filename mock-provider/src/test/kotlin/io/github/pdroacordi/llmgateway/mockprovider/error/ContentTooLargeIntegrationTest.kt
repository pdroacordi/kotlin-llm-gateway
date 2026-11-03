package io.github.pdroacordi.llmgateway.mockprovider.error

import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.bodyText
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chat
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chatBody
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.json
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.messages
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.messagesBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.http.codecs.max-in-memory-size=1KB"],
)
@AutoConfigureWebTestClient
class ContentTooLargeIntegrationTest(
    @Autowired private val client: WebTestClient,
) {
    @Test
    fun `a body over the codec limit is 413 in the route's shape`() {
        val openAi = client.chat(chatBody("x".repeat(2048)))
        val anthropic = client.messages(messagesBody("x".repeat(2048)))

        assertEquals(413, openAi.status.value())
        assertEquals(
            """{"error":{"message":"The request body is larger than mock-provider accepts.",""" +
                """"type":"invalid_request_error",""" +
                """"param":null,"code":null}}""",
            openAi.bodyText(),
        )
        assertEquals(413, anthropic.status.value())
        assertEquals("request_too_large", anthropic.json<String>("$.error.type"))
    }
}
