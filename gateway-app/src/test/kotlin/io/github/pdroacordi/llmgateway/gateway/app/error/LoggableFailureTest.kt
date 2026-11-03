package io.github.pdroacordi.llmgateway.gateway.app.error

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.io.IOException

class LoggableFailureTest {
    @Test
    fun `the copy drops Reactor's checkpoint and keeps message, frames, cause and other suppressed exceptions`() {
        val failure = IllegalStateException("boom", IOException("socket"))
        failure.addSuppressed(IllegalArgumentException("kept"))
        // Taken before the checkpoint, which cuts the failure's own trace to its first frame.
        val frames = failure.stackTrace.copyOf()
        // Resumed before block(), which would add a suppressed exception of its own.
        Mono
            .error<Unit>(failure)
            .checkpoint("HTTP GET \"/v1/x?secret=1\"")
            .onErrorResume { Mono.empty() }
            .block()
        assertTrue(failure.suppressed.any { "secret=1" in it.toString() }) { "checkpoint not attached" }
        assertEquals(1, failure.stackTrace.size) { "the checkpoint no longer truncates; this test proves nothing" }

        val copy = LoggableFailure.of(failure)

        assertEquals("java.lang.IllegalStateException: boom", copy.message)
        assertArrayEquals(frames, copy.stackTrace)
        assertTrue(copy.stackTrace.any { it.className == LoggableFailureTest::class.java.name })
        assertEquals("java.io.IOException: socket", copy.cause?.message)
        assertEquals(listOf("java.lang.IllegalArgumentException: kept"), copy.suppressed.map { it.message })
        assertTrue("secret=1" !in copy.stackTraceToString()) { copy.stackTraceToString() }
    }
}
