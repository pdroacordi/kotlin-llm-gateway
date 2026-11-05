package io.github.pdroacordi.llmgateway.gateway.core.embedding

/** Where CPU work that grows with a body runs, off the event loops and out of chat's way (ADR-GW-05-012). */
interface EmbeddingCpuPort {
    /**
     * Admission to the lane, for a request's parse. Throws [java.util.concurrent.TimeoutException] past its deadline
     * and [java.util.concurrent.RejectedExecutionException] when full or closed. The caller resumes on [work]'s thread.
     */
    suspend fun <T : Any> run(work: () -> T?): T?
}
