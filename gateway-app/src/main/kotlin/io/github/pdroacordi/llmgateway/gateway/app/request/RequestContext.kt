package io.github.pdroacordi.llmgateway.gateway.app.request

import io.github.pdroacordi.llmgateway.gateway.core.request.ClientRequestId
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-request values, read in coroutine code as `currentCoroutineContext()[RequestContext]` and elsewhere from the
 * exchange attribute [ATTRIBUTE] (docs/knowledge/patterns/request-context-coroutine-element.md).
 */
class RequestContext(
    val requestId: RequestId,
    val clientRequestId: ClientRequestId?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestContext> {
        const val ATTRIBUTE = "io.github.pdroacordi.llmgateway.gateway.app.request.RequestContext"
    }
}
