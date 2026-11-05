package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.app.isolatedGateway
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelResolver
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.chat.RoutingChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingModelPort
import io.github.pdroacordi.llmgateway.gateway.core.embedding.RoutingEmbeddingModelPort
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.github.pdroacordi.llmgateway.gateway.providers.anthropic.AnthropicChatAdapter
import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiChatAdapter
import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiEmbeddingAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** ADR-GW-03-004: what each combination of enabled providers starts, or why it stops. */
@ExtendWith(OutputCaptureExtension::class)
class ProviderStartupTest {
    private fun context(vararg args: String): ConfigurableApplicationContext =
        isolatedGateway().run("--server.port=0", *args)

    private fun failure(vararg args: String): String =
        generateSequence(assertThrows<Throwable> { context(*args).close() }) { it.cause }
            .joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `the default start has OpenAI only, needs no Anthropic key, and claude-x stays on OpenAI`() {
        context().use { context ->
            assertEquals(1, context.getBeanNamesForType(OpenAiChatAdapter::class.java).size)
            assertEquals(0, context.getBeanNamesForType(AnthropicChatAdapter::class.java).size)
            assertEquals(ProviderId("openai"), resolve(context, "claude-x"))
        }
    }

    @Test
    fun `with Anthropic enabled its prefixes go to it, the rest to the default`() {
        val enabled = arrayOf("--llmgw.providers.anthropic.enabled=true", "--llmgw.providers.anthropic.api-key=k")
        context(*enabled).use { context ->
            assertEquals(ProviderId("anthropic"), resolve(context, "claude-x"))
            assertEquals(ProviderId("anthropic"), resolve(context, "mock-claude"))
            assertEquals(ProviderId("openai"), resolve(context, "mock-chat"))
            assertTrue(context.getBean(ChatModelPort::class.java) is RoutingChatModelPort)
        }
    }

    @Test
    fun `an Anthropic-only start works with Anthropic as the default`() {
        val args =
            arrayOf(
                "--llmgw.providers.openai.enabled=false",
                "--llmgw.providers.openai.api-key=",
                "--llmgw.providers.anthropic.enabled=true",
                "--llmgw.providers.anthropic.api-key=k",
                "--llmgw.providers.default-provider=anthropic",
            )
        context(*args).use { context ->
            assertEquals(0, context.getBeanNamesForType(OpenAiChatAdapter::class.java).size)
            assertEquals(ProviderId("anthropic"), resolve(context, "anything"))
        }
    }

    @Test
    fun `the default start has the embedding adapter behind the embedding router`() {
        context().use { context ->
            assertEquals(1, context.getBeanNamesForType(OpenAiEmbeddingAdapter::class.java).size)
            assertTrue(context.getBean(EmbeddingModelPort::class.java) is RoutingEmbeddingModelPort)
        }
    }

    @Test
    fun `with OpenAI off and no key the app starts and the embeddings route gives the 400 for the model`() {
        val args =
            arrayOf(
                "--llmgw.providers.openai.enabled=false",
                "--llmgw.providers.openai.api-key=",
                "--llmgw.providers.openai.embeddings.request-timeout=0s",
                "--llmgw.providers.anthropic.enabled=true",
                "--llmgw.providers.anthropic.api-key=k",
                "--llmgw.providers.default-provider=anthropic",
            )
        context(*args).use { context ->
            assertEquals(0, context.getBeanNamesForType(OpenAiEmbeddingAdapter::class.java).size)

            val response = embed(context)

            assertEquals(400, response.statusCode()) { response.body() }
            val error =
                JsonMapper
                    .builder()
                    .build()
                    .readTree(response.body())
                    .get("error")
            assertEquals("unsupported_value", error.get("code").stringValue())
            assertEquals("model", error.get("param").stringValue())
        }
    }

    @Test
    fun `a bad embeddings value stops the start when OpenAI is enabled, naming the property`() {
        val chain = failure("--llmgw.providers.openai.embeddings.max-connections=0")

        assertTrue("llmgw.providers.openai.embeddings.max-connections" in chain) { chain }
    }

    @Test
    fun `a budget above half the heap stops the start, naming the property`() {
        val message = failure("--llmgw.providers.openai.embeddings.max-in-flight-bytes=1TB")

        assertTrue("llmgw.providers.openai.embeddings.max-in-flight-bytes" in message) { message }
    }

    @Test
    fun `a disabled provider's bad values stop nothing`() {
        val args =
            arrayOf(
                "--llmgw.providers.anthropic.api-key=",
                "--llmgw.providers.anthropic.version=latest",
                "--llmgw.providers.anthropic.default-max-tokens=0",
                "--llmgw.providers.anthropic.request-timeout=0s",
                "--llmgw.providers.anthropic.stream.idle-timeout=1s",
            )
        context(*args).use { assertTrue(it.isRunning) }
    }

    @Test
    fun `an enabled provider with a blank key stops the start, naming the variable`() {
        val chain = failure("--llmgw.providers.anthropic.enabled=true", "--llmgw.providers.anthropic.api-key=")

        assertTrue("ANTHROPIC_API_KEY" in chain) { chain }
    }

    @Test
    fun `a non-ASCII key stops the start, and neither the failure nor the log holds it`(output: CapturedOutput) {
        val chain =
            failure("--llmgw.providers.anthropic.enabled=true", "--llmgw.providers.anthropic.api-key=$CANARY\n")

        assertTrue("ANTHROPIC_API_KEY" in chain) { chain }
        assertFalse(CANARY in chain) { chain }
        assertFalse(CANARY in output.all) { output.all }
    }

    @Test
    fun `no provider enabled stops the start`() {
        val chain = failure("--llmgw.providers.openai.enabled=false")

        assertTrue("No provider is enabled" in chain) { chain }
    }

    @Test
    fun `a default provider that is not enabled stops the start`() {
        val chain = failure("--llmgw.providers.default-provider=anthropic")

        assertTrue("llmgw.providers.default-provider" in chain) { chain }
    }

    @Test
    fun `a bad version or token default stops the start when enabled`() {
        val enabled = arrayOf("--llmgw.providers.anthropic.enabled=true", "--llmgw.providers.anthropic.api-key=k")
        val version = failure(*enabled, "--llmgw.providers.anthropic.version=latest")
        val tokens = failure(*enabled, "--llmgw.providers.anthropic.default-max-tokens=0")

        assertTrue("llmgw.providers.anthropic.version" in version) { version }
        assertTrue("llmgw.providers.anthropic.default-max-tokens" in tokens) { tokens }
    }

    @Test
    fun `the write timeout must be below the lowest idle-timeout of the enabled providers`() {
        val chain =
            failure(
                "--llmgw.providers.anthropic.enabled=true",
                "--llmgw.providers.anthropic.api-key=k",
                "--llmgw.providers.anthropic.stream.idle-timeout=10s",
            )

        assertTrue("client-write-timeout" in chain) { chain }
    }

    @ParameterizedTest
    @ValueSource(strings = ["1", "yes", "on"])
    fun `a spelling of true that binds but makes no Anthropic adapter stops the start`(spelling: String) {
        val chain = failure("--llmgw.providers.anthropic.enabled=$spelling")

        assertTrue("llmgw.providers.anthropic.enabled" in chain) { chain }
    }

    @ParameterizedTest
    @ValueSource(strings = ["1", "yes", "on"])
    fun `a spelling of true that binds but makes no OpenAI adapter stops the start`(spelling: String) {
        val chain =
            failure(
                "--llmgw.providers.openai.enabled=$spelling",
                "--llmgw.providers.anthropic.enabled=true",
                "--llmgw.providers.anthropic.api-key=k",
                "--llmgw.providers.default-provider=anthropic",
            )

        assertTrue("llmgw.providers.openai.enabled" in chain) { chain }
    }

    private fun embed(context: ConfigurableApplicationContext): HttpResponse<String> {
        val port = context.environment.getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/v1/embeddings"))
                .header("Authorization", TestKeys.BEARER)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"model":"text-embedding-3-small","input":"a"}"""))
                .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun resolve(
        context: ConfigurableApplicationContext,
        model: String,
    ): ProviderId = context.getBean(ModelResolver::class.java).resolve(ModelName(model)).provider

    private companion object {
        const val CANARY = "sk-ant-canary"
    }
}
