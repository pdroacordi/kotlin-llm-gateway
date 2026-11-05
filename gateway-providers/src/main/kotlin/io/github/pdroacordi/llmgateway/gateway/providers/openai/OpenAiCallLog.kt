package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.ProviderErrorFields
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.providerRequestId
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.sourceName
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import java.time.Clock

/** What a non-stream OpenAI call logs, whichever adapter made it. [started] is in epoch ms. */
internal class OpenAiCallLog(
    owner: Class<*>,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(owner)

    fun answered(
        deployment: Deployment,
        response: ResponseEntity<ByteArray>,
        started: Long,
        usage: Usage?,
    ) {
        log.debug(
            "Provider call answered: deployment={} status={} providerRequestId={} durationMs={} " +
                "inputTokens={} outputTokens={} estimated={}",
            deployment.id.value,
            response.statusCode.value(),
            providerRequestId(response.headers, OPENAI_REQUEST_ID),
            clock.millis() - started,
            usage?.inputTokens?.value,
            usage?.outputTokens?.value,
            usage == null,
        )
    }

    fun failed(
        deployment: Deployment,
        response: ResponseEntity<ByteArray>,
        fields: ProviderErrorFields,
        started: Long,
    ) {
        log.warn(
            "Provider call failed: deployment={} status={} code={} type={} providerRequestId={} durationMs={}",
            deployment.id.value,
            response.statusCode.value(),
            fields.code,
            fields.type,
            providerRequestId(response.headers, OPENAI_REQUEST_ID),
            clock.millis() - started,
        )
    }

    fun broken(
        deployment: Deployment,
        cause: Throwable,
        started: Long,
    ) {
        log.warn(
            "Provider call failed: deployment={} exception={} durationMs={}",
            deployment.id.value,
            cause.sourceName(),
            clock.millis() - started,
        )
    }
}
