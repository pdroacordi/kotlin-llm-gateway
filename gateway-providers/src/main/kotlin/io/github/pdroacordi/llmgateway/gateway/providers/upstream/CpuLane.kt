package io.github.pdroacordi.llmgateway.gateway.providers.upstream

import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingCpuPort
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A bounded scheduler of its own for CPU work that grows with a body: off the event loops, and off `parallel`, whose
 * timers chat needs (ADR-GW-05-012). Its threads are non-blocking, so BlockHound still watches this path. [queue] is
 * how many stages may wait beyond [threads] before [run] refuses; a counter bounds it, since `newParallel`'s own queues
 * have no limit. Only [run] is refused: later stages count towards the queue and are bounded by the in-flight budget.
 */
class CpuLane(
    name: String,
    threads: Int,
    queue: Int,
    private val deadline: Duration,
) : EmbeddingCpuPort,
    AutoCloseable {
    private val scheduler = Schedulers.newParallel(name, threads, true)
    private val capacity = threads + queue
    private val pending = AtomicInteger()

    /**
     * Admission: the request's parse, refused past the queue. A cancel drops queued work. Running work finishes, its
     * result dropped: nothing in it polls an interrupt.
     */
    override suspend fun <T : Any> run(work: () -> T?): T? = admitted(work).awaitSingleOrNull()

    /** A stage of a call past [run], which holds a reservation: it waits its turn, and only [close] refuses it. */
    internal suspend fun <T : Any> runAdmitted(work: () -> T?): T? = continued(work).awaitSingleOrNull()

    /**
     * [buffer]'s bytes as an array, copied here and not on the event loop that joined them (ADR-GW-05-012). Takes
     * ownership: the buffer is released once, whether the copy runs, is refused or is cancelled while it waits.
     */
    internal fun copy(buffer: DataBuffer): Mono<ByteArray> {
        val owned = AtomicBoolean(true)
        return continued {
            // A cancel that won the buffer has released it; one that comes later finds it taken, so a running copy
            // never reads freed memory.
            if (owned.compareAndSet(true, false)) {
                try {
                    ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
                } finally {
                    DataBufferUtils.release(buffer)
                }
            } else {
                null
            }
        }.doFinally { if (owned.compareAndSet(true, false)) DataBufferUtils.release(buffer) }
    }

    private fun <T : Any> admitted(work: () -> T?): Mono<T> =
        Mono.defer {
            if (pending.incrementAndGet() > capacity) {
                pending.decrementAndGet()
                Mono.error(RejectedExecutionException("The CPU lane's queue is full."))
            } else {
                scheduled(work)
            }
        }

    // Counted, so a backlog of late stages closes admission (ADR-GW-05-012).
    private fun <T : Any> continued(work: () -> T?): Mono<T> =
        Mono.defer {
            pending.incrementAndGet()
            scheduled(work)
        }

    private fun <T : Any> scheduled(work: () -> T?): Mono<T> =
        Mono
            .fromCallable { work() }
            .subscribeOn(scheduler)
            // After subscribeOn, as a JVM-fatal error in [work] sends no signal: the timer is a separate task
            // (docs/knowledge/gotchas/reactor-jvm-fatal-error-leaves-the-subscriber-unsignalled.md).
            .timeout(deadline)
            .doFinally { pending.decrementAndGet() }

    override fun close() {
        scheduler.dispose()
    }
}
