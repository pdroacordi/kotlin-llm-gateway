package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.app.isolatedGateway
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import reactor.netty.http.HttpResources

/** The bug this guards: docs/knowledge/bugs/started-test-contexts-dispose-global-reactor-resources.md. */
class StartedContextIsolationTest {
    @Test
    fun `a context closing leaves the JVM's global Reactor resources in place`() {
        val before = HttpResources.get()

        isolatedGateway().run("--server.port=0").close()

        assertSame(before, HttpResources.get())
    }
}
