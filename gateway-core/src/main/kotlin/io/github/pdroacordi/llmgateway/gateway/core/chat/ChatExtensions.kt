package io.github.pdroacordi.llmgateway.gateway.core.chat

/** The gateway's own JSON member (ADR-GW-02-005, ADR-020). Adapters never forward it to a provider as is. */
object ChatExtensions {
    const val MEMBER = "llmgw"

    /** Inside [MEMBER]: `true` when the gateway estimated the usage beside it. */
    const val USAGE_ESTIMATED_MEMBER = "usage_estimated"
}
