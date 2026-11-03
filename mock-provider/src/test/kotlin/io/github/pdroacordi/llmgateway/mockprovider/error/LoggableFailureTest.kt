package io.github.pdroacordi.llmgateway.mockprovider.error

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class LoggableFailureTest {
    @Test
    fun `the copy drops the checkpoint text and gets back the frames the checkpoint took`() {
        val failure = IllegalStateException("boom")
        // Taken before the checkpoint, which cuts the failure's own trace to its first frame.
        val frames = failure.stackTrace.copyOf()
        Mono
            .error<Unit>(failure)
            .checkpoint("HTTP GET \"/v1/x?secret=1\"")
            .onErrorResume { Mono.empty() }
            .block()

        // Reactor's checkpoint cut the failure's own stack to one frame and moved the rest into the checkpoint.
        assertEquals(1, failure.stackTrace.size)
        val copy = LoggableFailure.of(failure)
        val logged = copy.stackTraceToString()

        assertFalse("secret=1" in logged) { logged }
        assertTrue("java.lang.IllegalStateException: boom" in logged)
        assertArrayEquals(frames, copy.stackTrace)
        assertTrue(copy.stackTrace.any { it.className == LoggableFailureTest::class.java.name })
    }

    @Test
    fun `causes and other suppressed exceptions are kept`() {
        val failure = IllegalStateException("outer", IllegalArgumentException("inner"))
        failure.addSuppressed(UnsupportedOperationException("side"))

        val logged = LoggableFailure.of(failure).stackTraceToString()

        assertTrue("IllegalArgumentException: inner" in logged)
        assertTrue("UnsupportedOperationException: side" in logged)
    }
}
