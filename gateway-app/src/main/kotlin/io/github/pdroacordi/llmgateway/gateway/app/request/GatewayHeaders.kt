package io.github.pdroacordi.llmgateway.gateway.app.request

/** Header names and MDC keys of the request context (FND-04 section 4). Contract values, not tunables. */
object GatewayHeaders {
    /** OpenAI's response header; always gateway-generated. */
    const val REQUEST_ID = "x-request-id"

    /** OpenAI's `X-Client-Request-Id`; validated and echoed. */
    const val CLIENT_REQUEST_ID = "x-client-request-id"

    /** Extension: which deployment answered, on a 200. */
    const val DEPLOYMENT = "x-gateway-deployment"

    /** Extension: `true` when the provider omitted usage and the gateway estimated it (ADR-GW-01-011). */
    const val USAGE_ESTIMATED = "x-gateway-usage-estimated"

    /** nginx honours it per response: one header removes the commonest cause of a buffered stream. */
    const val ACCEL_BUFFERING = "x-accel-buffering"

    // Must match logging.pattern.correlation in application.yaml.
    const val MDC_REQUEST_ID = "requestId"
    const val MDC_CLIENT_REQUEST_ID = "clientRequestId"
}
