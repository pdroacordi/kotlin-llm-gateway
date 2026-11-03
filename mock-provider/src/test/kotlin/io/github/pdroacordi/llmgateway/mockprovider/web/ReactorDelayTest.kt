package io.github.pdroacordi.llmgateway.mockprovider.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Spring starts coRouter handlers and CoWebFilters on Dispatchers.Unconfined; these tests reproduce that context
// (docs/knowledge/gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay.md).
@Suppress("InjectDispatcher")
class ReactorDelayTest {
    // The thread it does resume on differs by platform, so only this is pinned.
    @Test
    fun `kotlinx delay under Unconfined does not resume on a Reactor parallel thread`() {
        val thread = runBlocking { withContext(Dispatchers.Unconfined) { delay(WAIT_MS).let { threadName() } } }

        assertFalse(thread.startsWith("parallel-")) { thread }
    }

    @Test
    fun `awaitDelay under Unconfined resumes on a Reactor parallel thread`() {
        val thread = runBlocking { withContext(Dispatchers.Unconfined) { awaitDelay(WAIT_MS).let { threadName() } } }

        assertTrue(thread.startsWith("parallel-")) { thread }
    }

    private fun threadName(): String = Thread.currentThread().name

    private companion object {
        const val WAIT_MS = 10L
    }
}
