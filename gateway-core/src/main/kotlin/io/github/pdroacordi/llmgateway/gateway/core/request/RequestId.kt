package io.github.pdroacordi.llmgateway.gateway.core.request

import kotlin.uuid.Uuid

/** The gateway's ID for one request: a UUIDv7, opaque to clients (ADR-FND-04-002). */
@JvmInline
value class RequestId(
    val value: Uuid,
) {
    /** Canonical lowercase hex-and-dash, 36 characters. */
    override fun toString(): String = value.toString()
}
