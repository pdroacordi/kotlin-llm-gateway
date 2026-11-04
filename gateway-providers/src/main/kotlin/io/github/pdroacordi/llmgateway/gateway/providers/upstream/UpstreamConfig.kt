package io.github.pdroacordi.llmgateway.gateway.providers.upstream

import java.time.Duration

data class PoolConfig(
    val maxConnections: Int,
    val pendingAcquireTimeout: Duration,
    val maxIdleTime: Duration,
    val maxLifeTime: Duration,
)

/** The deadlines of ADR-GW-02-006; [maxEventBytes] bounds one SSE event, not a stream. */
data class StreamConfig(
    val firstByteTimeout: Duration,
    val idleTimeout: Duration,
    val totalTimeout: Duration,
    val maxEventBytes: Int,
)
