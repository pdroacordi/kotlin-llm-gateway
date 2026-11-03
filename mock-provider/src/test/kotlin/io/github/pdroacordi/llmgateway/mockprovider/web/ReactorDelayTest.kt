package io.github.pdroacordi.llmgateway.mockprovider.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Spring starts coRouter handlers and CoWebFilters on Dispatchers.Unconfined; these tests reproduce that context
// (docs/knowledge/gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay.md).
@Suppress("InjectDispatcher")
class ReactorDelayTest {
    @Test
    fun `kotlinx delay under Unconfined resumes on DefaultExecutor, the thread BlockHound does not watch`() {
        val thread = runBlocking { withContext(Dispatchers.Unconfined) { delay(WAIT_MS).let { threadName() } } }

        assertTrue("DefaultExecutor" in thread) { thread }
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
