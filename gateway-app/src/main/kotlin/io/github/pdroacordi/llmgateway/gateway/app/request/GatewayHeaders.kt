package io.github.pdroacordi.llmgateway.gateway.app.request

/** Header names and MDC keys of the request context (FND-04 section 4). Contract values, not tunables. */
object GatewayHeaders {
    /** OpenAI's response header; always gateway-generated. */
    const val REQUEST_ID = "x-request-id"

    /** OpenAI's `X-Client-Request-Id`; validated and echoed. */
    const val CLIENT_REQUEST_ID = "x-client-request-id"

    // Must match logging.pattern.correlation in application.yaml.
    const val MDC_REQUEST_ID = "requestId"
    const val MDC_CLIENT_REQUEST_ID = "clientRequestId"
}
