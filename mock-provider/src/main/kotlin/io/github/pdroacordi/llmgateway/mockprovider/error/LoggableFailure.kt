package io.github.pdroacordi.llmgateway.mockprovider.error

import java.util.Collections
import java.util.IdentityHashMap

private const val MAX_DEPTH = 32
private const val REACTOR_CHECKPOINT = "reactor.core.publisher.FluxOnAssembly\$OnAssemblyException"

/**
 * A copy of a failure chain, for logging, without Reactor's assembly checkpoints: Spring attaches one naming the raw
 * query string to every error
 * (docs/knowledge/gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query.md).
 * The frames are taken back from the checkpoint. Identical to gateway-app's copy; the mock imports nothing of ours
 * (ADR-FND-03-003), so the two are kept in step by hand.
 */
internal class LoggableFailure private constructor(
    description: String,
    cause: Throwable?,
) : RuntimeException(description, cause) {
    companion object {
        fun of(failure: Throwable): Throwable =
            copyOf(failure, 0, Collections.newSetFromMap<Throwable>(IdentityHashMap()).apply { add(failure) })

        // `seen` stops a cycle through cause and suppressed, which depth alone would walk 2^MAX_DEPTH times.
        private fun copyOf(
            failure: Throwable,
            depth: Int,
            seen: MutableSet<Throwable>,
        ): Throwable {
            val cause = failure.cause?.takeIf { depth < MAX_DEPTH && seen.add(it) }?.let { copyOf(it, depth + 1, seen) }
            val copy = LoggableFailure(failure.toString(), cause)
            val (checkpoints, others) = failure.suppressed.partition { it.javaClass.name == REACTOR_CHECKPOINT }
            // A checkpoint cut the failure's own trace to its first frame and holds the full one
            // (docs/knowledge/gotchas/reactor-checkpoint-truncates-error-stack-to-first-frame.md).
            copy.stackTrace =
                checkpoints
                    .map { it.stackTrace }
                    .firstOrNull { it.size > failure.stackTrace.size }
                    ?: failure.stackTrace
            others
                .takeIf { depth < MAX_DEPTH }
                ?.filter(seen::add)
                ?.forEach { copy.addSuppressed(copyOf(it, depth + 1, seen)) }
            return copy
        }
    }
}
