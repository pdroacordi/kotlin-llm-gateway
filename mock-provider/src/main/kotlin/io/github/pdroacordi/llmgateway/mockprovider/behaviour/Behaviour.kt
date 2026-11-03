package io.github.pdroacordi.llmgateway.mockprovider.behaviour

/** All latency values in ms. A null field is inherited (FND-03 section 4, resolution). */
data class Latency(
    val ttftMs: Long? = null,
    val perTokenMs: Long? = null,
    val jitterMs: Long? = null,
)

/** An HTTP error in the route's provider shape; null fields take the defaults of `ProviderErrors`. */
data class InjectedError(
    val status: Int,
    val type: String? = null,
    val code: String? = null,
    val message: String? = null,
    val param: String? = null,
    val retryAfterSeconds: Long? = null,
    val retryAfterMs: Long? = null,
    val headers: Map<String, String> = emptyMap(),
)

enum class FaultKind(
    val wire: String,
) {
    HANG("hang"),
    STALL("stall"),
    DISCONNECT("disconnect"),
    STREAM_ERROR("stream_error"),
    MALFORMED("malformed"),
    ;

    companion object {
        fun fromWire(value: String): FaultKind? = entries.firstOrNull { it.wire == value }
    }
}

data class Fault(
    val kind: FaultKind,
    val afterPieces: Int = 0,
)

enum class ContentMode(
    val wire: String,
) {
    LOREM("lorem"),
    ECHO("echo"),
    FIXED("fixed"),
    ;

    companion object {
        fun fromWire(value: String): ContentMode? = entries.firstOrNull { it.wire == value }
    }
}

data class ContentSpec(
    val mode: ContentMode? = null,
    val text: String? = null,
    val completionTokens: Int? = null,
)

data class UsageSpec(
    val omit: Boolean? = null,
    val cachedTokens: Int? = null,
    val cacheCreationTokens: Int? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

/** What a scenario step, a scenario's `otherwise` or the configured defaults say; every member may be unset. */
data class Behaviour(
    val latency: Latency? = null,
    val error: InjectedError? = null,
    val fault: Fault? = null,
    val content: ContentSpec? = null,
    val usage: UsageSpec? = null,
)

data class ResolvedLatency(
    val ttftMs: Long,
    val perTokenMs: Long,
    val jitterMs: Long,
)

data class ResolvedContent(
    val mode: ContentMode,
    val text: String?,
    val completionTokens: Int,
)

data class ResolvedUsage(
    val omit: Boolean,
    val cachedTokens: Int,
    val cacheCreationTokens: Int,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

/**
 * The behaviour one request gets. [scenarioModel] is the key of the scenario that applied (`*` included), null when
 * none did; [step] is the zero-based step index, null when `otherwise` or the defaults applied.
 */
data class ResolvedBehaviour(
    val latency: ResolvedLatency,
    val error: InjectedError?,
    val fault: Fault?,
    val content: ResolvedContent,
    val usage: ResolvedUsage,
    val scenarioModel: String?,
    val step: Int?,
)
