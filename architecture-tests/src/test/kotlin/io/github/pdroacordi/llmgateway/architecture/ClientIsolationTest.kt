package io.github.pdroacordi.llmgateway.architecture

import org.junit.jupiter.api.Test

class ClientIsolationTest {
    @Test
    fun `ARCH-4 agent modules import nothing from the gateway`() {
        assertNoViolations(agentDoesNotImportGateway(ProductionScope.files))
    }

    @Test
    fun `ARCH-5 the cli imports nothing from the gateway or the agent runtime`() {
        assertNoViolations(clientDoesNotImportServers(ProductionScope.files))
    }
}
