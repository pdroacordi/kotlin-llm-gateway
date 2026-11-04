package io.github.pdroacordi.llmgateway.gateway.core.request

import java.time.Clock
import kotlin.random.Random

/** Builds request IDs from [clock] and [random]; the layout is [UuidV7Generator]'s (ADR-FND-04-002). */
class RequestIdGenerator(
    clock: Clock,
    random: Random,
) {
    private val ids = UuidV7Generator(clock, random)

    fun next(): RequestId = RequestId(ids.next())
}
