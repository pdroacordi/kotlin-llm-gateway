package io.github.pdroacordi.llmgateway.gateway.app.error

import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId

/**
 * Stands in for the failure after commit, once the mapper has logged it. Stackless and without suppression, so no
 * Reactor checkpoint rides on it into Reactor Netty's log line, and its message holds no phrase that would make
 * Spring end the response normally
 * (docs/knowledge/gotchas/webflux-committed-error-reaches-netty-log-or-completes-normally.md).
 */
internal class CommittedResponseAbort(
    requestId: RequestId,
) : RuntimeException("Aborted after commit, request $requestId", null, false, false)
