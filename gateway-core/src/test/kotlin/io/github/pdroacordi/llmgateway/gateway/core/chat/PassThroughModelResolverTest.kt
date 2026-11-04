package io.github.pdroacordi.llmgateway.gateway.core.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PassThroughModelResolverTest {
    @Test
    fun `every model goes to the one deployment under the name the client sent`() {
        val resolver = PassThroughModelResolver(DeploymentId("openai"), ProviderId("openai"))

        assertEquals(
            Deployment(DeploymentId("openai"), ProviderId("openai"), ModelName("anything-at-all")),
            resolver.resolve(ModelName("anything-at-all")),
        )
    }
}
