package io.github.pdroacordi.llmgateway.gateway.app.error

private const val MAX_DEPTH = 32
private const val REACTOR_CHECKPOINT = "reactor.core.publisher.FluxOnAssembly\$OnAssemblyException"

/**
 * A copy of a failure chain, for logging, without Reactor's assembly checkpoints. Spring's ExceptionHandlingWebHandler
 * attaches one to every error, and its text holds the raw query string, which is never logged (FND-04 section 5).
 * Each copy's message is the original's `toString()`; frames (taken back from the checkpoint), causes and other
 * suppressed exceptions are kept. mock-provider keeps an identical copy (it may not import this module, ARCH-9).
 */
internal class LoggableFailure private constructor(
    description: String,
    cause: Throwable?,
) : RuntimeException(description, cause) {
    companion object {
        fun of(failure: Throwable): Throwable = copyOf(failure, 0)

        private fun copyOf(
            failure: Throwable,
            depth: Int,
        ): Throwable {
            val cause = failure.cause?.takeIf { depth < MAX_DEPTH }?.let { copyOf(it, depth + 1) }
            val copy = LoggableFailure(failure.toString(), cause)
            val (checkpoints, others) = failure.suppressed.partition { it.javaClass.name == REACTOR_CHECKPOINT }
            // A checkpoint cut the failure's own trace to its first frame and holds the full one
            // (docs/knowledge/gotchas/reactor-checkpoint-truncates-error-stack-to-first-frame.md).
            copy.stackTrace =
                checkpoints
                    .map { it.stackTrace }
                    .firstOrNull { it.size > failure.stackTrace.size }
                    ?: failure.stackTrace
            others.takeIf { depth < MAX_DEPTH }?.forEach { copy.addSuppressed(copyOf(it, depth + 1)) }
            return copy
        }
    }
}
