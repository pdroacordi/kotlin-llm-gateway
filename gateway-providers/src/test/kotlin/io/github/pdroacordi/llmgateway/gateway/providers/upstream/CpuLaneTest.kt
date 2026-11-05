package io.github.pdroacordi.llmgateway.gateway.providers.upstream

import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledByteBufAllocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.buffer.NettyDataBuffer
import org.springframework.core.io.buffer.NettyDataBufferFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Spring starts coroutine handlers Unconfined, so the lane's callers run as these do.
@Suppress("InjectDispatcher")
@Timeout(30)
class CpuLaneTest {
    private val lane = CpuLane(NAME, THREADS, QUEUE, Duration.ofMillis(300))

    @AfterEach
    fun closeLane() {
        lane.close()
    }

    @Test
    fun `work runs on a thread named for the lane, and an Unconfined caller resumes there`() {
        val threads =
            runBlocking(Dispatchers.Unconfined) {
                val worker = lane.run { Thread.currentThread() }
                worker to Thread.currentThread()
            }

        assertTrue(checkNotNull(threads.first).name.startsWith("$NAME-")) { threads.first?.name.orEmpty() }
        assertEquals(threads.first, threads.second)
    }

    @Test
    fun `a null result is null`() {
        assertNull(runBlocking { lane.run<String> { null } })
    }

    @Test
    fun `an exception reaches the caller`() {
        assertThrows<IllegalArgumentException> {
            runBlocking { lane.run<String> { throw IllegalArgumentException("x") } }
        }
    }

    // docs/knowledge/gotchas/reactor-jvm-fatal-error-leaves-the-subscriber-unsignalled.md
    @Test
    fun `a JVM-fatal error, which sends no signal, ends the call at the deadline`() {
        assertTimeoutPreemptively(HANG_LIMIT) {
            assertThrows<TimeoutException> {
                runBlocking { lane.run<String> { throw OutOfMemoryError("not a real one") } }
            }
        }
    }

    @Test
    fun `the lane still works after a JVM-fatal error on each of its threads`() {
        repeat(THREADS) {
            assertTimeoutPreemptively(HANG_LIMIT) {
                assertThrows<TimeoutException> {
                    runBlocking { lane.run<String> { throw OutOfMemoryError("not a real one") } }
                }
            }
        }

        assertEquals(1, runBlocking { lane.run { 1 } })
    }

    @Test
    fun `a cancelled call leaves work already running to finish, and drops its result`() {
        val started = CountDownLatch(1)
        val finish = AtomicBoolean()
        val finished = CountDownLatch(1)
        val spins = AtomicLong()

        runBlocking {
            val call =
                async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
                    lane.run {
                        started.countDown()
                        // No onSpinWait or sleep: BlockHound refuses both on a lane thread.
                        while (!finish.get()) spins.incrementAndGet()
                        finished.countDown()
                    }
                }
            // Inside the block, and the join bounded: runBlocking waits for a child that ignores a cancel, and
            // @Timeout's interrupt cannot end it.
            try {
                assertTrue(started.await(LATCH_SECONDS, TimeUnit.SECONDS))
                call.cancel()
                withTimeout(JOIN_LIMIT_MS) { call.join() }
                assertEquals(1L, finished.count)
            } finally {
                finish.set(true)
            }
        }

        assertTrue(finished.await(LATCH_SECONDS, TimeUnit.SECONDS))
    }

    /** [THREADS] stages that spin until [Occupied.release]: no sleep or latch, as BlockHound refuses both here. */
    private inner class Occupied(
        private val target: CpuLane,
    ) {
        private val free = AtomicBoolean()
        private val running = CountDownLatch(THREADS)
        private val spins = AtomicLong()

        fun CoroutineScope.start() =
            List(THREADS) {
                async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
                    target.run {
                        running.countDown()
                        while (!free.get()) spins.incrementAndGet()
                    }
                }
            }.also { assertTrue(running.await(LATCH_SECONDS, TimeUnit.SECONDS)) }

        fun release() = free.set(true)
    }

    @Test
    fun `a full queue refuses work at once, and takes it again once it drains`() {
        CpuLane(NAME, THREADS, 1, LONG_DEADLINE).use { queued ->
            val occupied = Occupied(queued)
            runBlocking {
                try {
                    with(occupied) { start() }
                    val waiting = async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { queued.run { 1 } }

                    assertThrows<RejectedExecutionException> {
                        runBlocking(Dispatchers.Unconfined) { queued.run { 2 } }
                    }

                    occupied.release()
                    assertEquals(1, withTimeout(JOIN_LIMIT_MS) { waiting.await() })
                } finally {
                    occupied.release()
                }
            }
            assertEquals(3, runBlocking { queued.run { 3 } })
        }
    }

    @Test
    fun `a queued stage never starts once its call is cancelled, and leaves the queue at once`() {
        CpuLane(NAME, THREADS, 1, LONG_DEADLINE).use { queued ->
            val started = AtomicBoolean()
            val occupied = Occupied(queued)
            runBlocking {
                try {
                    with(occupied) { start() }
                    val waiting =
                        async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
                            queued.run { started.set(true) }
                        }

                    waiting.cancel()
                    withTimeout(JOIN_LIMIT_MS) { waiting.join() }
                    val next = async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { queued.run { 1 } }
                    occupied.release()

                    assertEquals(1, withTimeout(JOIN_LIMIT_MS) { next.await() })
                } finally {
                    occupied.release()
                }
            }
            repeat(THREADS) { runBlocking { queued.run { 1 } } }
            assertFalse(started.get())
        }
    }

    @Test
    fun `a full queue never refuses a reply's copy, which waits its turn`() {
        CpuLane(NAME, THREADS, 1, LONG_DEADLINE).use { queued ->
            val buffer = buffer()
            val occupied = Occupied(queued)
            runBlocking {
                try {
                    with(occupied) { start() }
                    val waiting = async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { queued.run { 1 } }

                    val copy = queued.copy(buffer).toFuture()

                    occupied.release()
                    assertEquals(BODY, copy.get(LATCH_SECONDS, TimeUnit.SECONDS)?.decodeToString())
                    assertEquals(1, withTimeout(JOIN_LIMIT_MS) { waiting.await() })
                } finally {
                    occupied.release()
                }
            }
            assertEquals(0, buffer.nativeBuffer.refCnt())
        }
    }

    @Test
    fun `a stage past admission waits out a full queue, and closes admission while it waits`() {
        CpuLane(NAME, THREADS, 1, LONG_DEADLINE).use { queued ->
            val occupied = Occupied(queued)
            runBlocking {
                try {
                    with(occupied) { start() }
                    val late =
                        List(LATE_STAGES) {
                            async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { queued.runAdmitted { 1 } }
                        }

                    assertThrows<RejectedExecutionException> {
                        runBlocking(Dispatchers.Unconfined) { queued.run { 2 } }
                    }

                    occupied.release()
                    assertEquals(List(LATE_STAGES) { 1 }, withTimeout(JOIN_LIMIT_MS) { late.awaitAll() })
                } finally {
                    occupied.release()
                }
            }
            assertEquals(3, runBlocking { queued.run { 3 } })
        }
    }

    @Test
    fun `a closed lane refuses a stage past admission`() {
        lane.close()

        assertThrows<RejectedExecutionException> { runBlocking { lane.runAdmitted { 1 } } }
    }

    @Test
    fun `a body is copied on the lane, and its buffer released`() {
        val buffer = buffer()

        val copy = lane.copy(buffer).block(BLOCK_LIMIT)

        assertEquals(BODY, copy?.decodeToString())
        assertEquals(0, buffer.nativeBuffer.refCnt())
    }

    @Test
    fun `a cancel before the copy starts releases the buffer, which is never read`() {
        CpuLane(NAME, THREADS, 1, LONG_DEADLINE).use { queued ->
            val buffer = buffer()
            val occupied = Occupied(queued)
            runBlocking {
                try {
                    with(occupied) { start() }

                    queued.copy(buffer).subscribe().dispose()

                    assertEquals(0, buffer.nativeBuffer.refCnt())
                } finally {
                    occupied.release()
                }
            }
        }
    }

    @Test
    fun `a lane that refuses the copy releases the buffer`() {
        val buffer = buffer()
        lane.close()

        assertThrows<RejectedExecutionException> { lane.copy(buffer).block(BLOCK_LIMIT) }

        assertEquals(0, buffer.nativeBuffer.refCnt())
    }

    private fun buffer(): NettyDataBuffer =
        NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT).wrap(Unpooled.copiedBuffer(BODY, Charsets.UTF_8))

    @Test
    fun `close disposes the scheduler, so later work is refused`() {
        lane.close()

        assertThrows<RejectedExecutionException> { runBlocking { lane.run { 1 } } }
    }

    private companion object {
        const val NAME = "test-lane"
        const val THREADS = 2
        const val QUEUE = 8
        const val LATE_STAGES = 5
        const val BODY = "a body"
        val LONG_DEADLINE: Duration = Duration.ofSeconds(20)
        val BLOCK_LIMIT: Duration = Duration.ofSeconds(5)
        const val LATCH_SECONDS = 5L
        const val JOIN_LIMIT_MS = 2000L
        val HANG_LIMIT: Duration = Duration.ofSeconds(3)
    }
}
