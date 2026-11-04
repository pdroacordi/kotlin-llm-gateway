package io.github.pdroacordi.llmgateway.gateway.app.web

/** The bytes of one SSE event, in OpenAI's framing (docs/knowledge/patterns/raw-sse-frames-flushed-per-event.md). */
object SseFrames {
    private const val LF: Byte = 0x0A
    private const val CR: Byte = 0x0D
    private val PREFIX = "data: ".encodeToByteArray()
    private val SUFFIX = "\n\n".encodeToByteArray()

    /** Never mutate: every stream shares it. */
    val DONE: ByteArray = data("[DONE]".encodeToByteArray())

    /** A raw line break would split the event in two; compact JSON never holds one. */
    fun data(json: ByteArray): ByteArray {
        require(json.none { it == LF || it == CR }) { "An SSE data value cannot hold a line break." }
        return PREFIX + json + SUFFIX
    }
}
