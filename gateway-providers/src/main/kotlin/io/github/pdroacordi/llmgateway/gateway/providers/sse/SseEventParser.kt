package io.github.pdroacordi.llmgateway.gateway.providers.sse

import java.io.ByteArrayOutputStream

/** [event] is null when the event names none; `id` and `retry` are dropped, as a provider is never reconnected to. */
internal class SseEvent(
    val event: String?,
    val data: ByteArray,
) {
    override fun toString(): String = "SseEvent(event=$event, bytes=${data.size})"
}

/** [overflowed]: an event went over the limit. [events] holds what came before it, and nothing follows it. */
internal class SseFeed(
    val events: List<SseEvent>,
    val overflowed: Boolean,
)

/**
 * The WHATWG event-stream format over bytes (ADR-GW-02-003): CR and LF never occur inside a UTF-8 sequence, so a
 * split character stays whole. [maxEventBytes] counts an event's lines without their line breaks.
 */
internal class SseEventParser(
    private val maxEventBytes: Int,
) {
    private val line = ByteArrayOutputStream()
    private val data = ByteArrayOutputStream()
    private var name: String? = null
    private var hasData = false
    private var eventBytes = 0
    private var skipLineFeed = false
    private var bomMatched = 0
    private var bomDone = false
    private var overflowed = false

    fun feed(bytes: ByteArray): SseFeed {
        val events = mutableListOf<SseEvent>()
        for (byte in bytes) {
            if (overflowed) break
            if (bomDone) accept(byte, events) else checkBom(byte, events)
        }
        return SseFeed(events, overflowed)
    }

    private fun checkBom(
        byte: Byte,
        events: MutableList<SseEvent>,
    ) {
        if (byte == BOM[bomMatched]) {
            bomMatched++
            bomDone = bomMatched == BOM.size
            return
        }
        bomDone = true
        for (index in 0 until bomMatched) accept(BOM[index], events)
        accept(byte, events)
    }

    private fun accept(
        byte: Byte,
        events: MutableList<SseEvent>,
    ) {
        val carriedOver = skipLineFeed
        skipLineFeed = false
        when (byte) {
            LF -> {
                if (!carriedOver) endLine(events)
            }

            CR -> {
                skipLineFeed = true
                endLine(events)
            }

            else -> {
                append(byte)
            }
        }
    }

    private fun append(byte: Byte) {
        eventBytes++
        if (eventBytes > maxEventBytes) {
            overflowed = true
            line.reset()
            data.reset()
        } else {
            line.write(byte.toInt())
        }
    }

    private fun endLine(events: MutableList<SseEvent>) {
        val bytes = line.toByteArray()
        line.reset()
        if (bytes.isEmpty()) dispatch(events) else field(bytes)
    }

    private fun dispatch(events: MutableList<SseEvent>) {
        if (hasData) events += SseEvent(name, data.toByteArray())
        data.reset()
        name = null
        hasData = false
        eventBytes = 0
    }

    private fun field(bytes: ByteArray) {
        val colon = bytes.indexOf(COLON)
        if (colon == 0) return
        val nameEnd = if (colon < 0) bytes.size else colon
        val valueStart =
            when {
                colon < 0 -> bytes.size
                colon + 1 < bytes.size && bytes[colon + 1] == SPACE -> colon + 2
                else -> colon + 1
            }
        when (bytes.decodeToString(0, nameEnd)) {
            "data" -> {
                if (hasData) data.write(LF.toInt())
                data.write(bytes, valueStart, bytes.size - valueStart)
                hasData = true
            }

            "event" -> {
                name = bytes.decodeToString(valueStart, bytes.size).ifEmpty { null }
            }
        }
    }

    private companion object {
        const val LF: Byte = 0x0A
        const val CR: Byte = 0x0D
        const val COLON: Byte = 0x3A
        const val SPACE: Byte = 0x20
        val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
