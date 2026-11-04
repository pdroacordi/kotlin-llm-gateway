package io.github.pdroacordi.llmgateway.gateway.providers

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.blockhound.BlockingOperationError
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

class BlockHoundInstalledTest {
    @Test
    fun `a blocking call on a non-blocking thread fails`() {
        val error =
            assertThrows<Throwable> {
                Mono
                    .fromCallable { Thread.sleep(1) }
                    .subscribeOn(Schedulers.parallel())
                    .block()
            }

        assertTrue(generateSequence(error) { it.cause }.any { it is BlockingOperationError }) {
            error.stackTraceToString()
        }
    }
}
