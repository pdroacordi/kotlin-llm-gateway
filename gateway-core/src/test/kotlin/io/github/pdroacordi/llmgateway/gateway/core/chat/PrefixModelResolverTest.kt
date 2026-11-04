package io.github.pdroacordi.llmgateway.gateway.core.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrefixModelResolverTest {
    private val openai = ProviderId("openai")
    private val anthropic = ProviderId("anthropic")

    @Test
    fun `no prefixes send every model to the default, under the name the client sent`() {
        val resolver = PrefixModelResolver(emptyList(), openai)

        assertEquals(
            Deployment(DeploymentId("openai"), openai, ModelName("claude-x")),
            resolver.resolve(ModelName("claude-x")),
        )
    }

    @Test
    fun `a matching prefix selects its provider and names the deployment after it`() {
        val resolver = PrefixModelResolver(listOf(ModelPrefix("claude-", anthropic)), openai)

        assertEquals(
            Deployment(DeploymentId("anthropic"), anthropic, ModelName("claude-x")),
            resolver.resolve(ModelName("claude-x")),
        )
    }

    @Test
    fun `the first matching prefix wins`() {
        val other = ProviderId("other")
        val prefixes = listOf(ModelPrefix("claude-", anthropic), ModelPrefix("claude-x", other))

        assertEquals(anthropic, PrefixModelResolver(prefixes, openai).resolve(ModelName("claude-x")).provider)
    }

    @Test
    fun `a prefix is case-sensitive and matches at the start only`() {
        val resolver = PrefixModelResolver(listOf(ModelPrefix("claude-", anthropic)), openai)

        assertEquals(openai, resolver.resolve(ModelName("Claude-x")).provider)
        assertEquals(openai, resolver.resolve(ModelName("my-claude-x")).provider)
    }
}
