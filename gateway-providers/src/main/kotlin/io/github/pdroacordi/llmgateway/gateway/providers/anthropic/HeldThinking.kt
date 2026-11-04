package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import org.slf4j.LoggerFactory

/**
 * Thinking blocks of one stream, assembled and held for the finish chunk (ADR-GW-03-011). Signatures have no stated
 * length, so [maxBytes] bounds what is held, open blocks included, and past it all are dropped: a paid answer outranks
 * a replay aid.
 */
internal class HeldThinking(
    private val maxBytes: Int,
) {
    private val open = mutableMapOf<Int, Block>()
    private val held = mutableListOf<JsonValue>()
    private var heldBytes = 0L
    private var dropped = false

    /** In order; empty if they were dropped. */
    val blocks: List<JsonValue> get() = held

    fun start(
        index: Int,
        text: String,
        signature: String,
    ) {
        if (fits(text.length.toLong() + signature.length)) open[index] = Block(text, signature)
    }

    fun appendText(
        index: Int,
        text: String,
    ) {
        open[index]?.let { block -> if (fits(text.length.toLong())) block.text.append(text) }
    }

    fun appendSignature(
        index: Int,
        signature: String,
    ) {
        open[index]?.let { block -> if (fits(signature.length.toLong())) block.signature.append(signature) }
    }

    fun stop(index: Int) {
        open.remove(index)?.let { held += it.toJsonValue() }
    }

    fun hold(block: JsonValue) {
        if (fits(block.size())) held += block
    }

    private fun fits(size: Long): Boolean {
        if (dropped) return false
        heldBytes += size
        if (heldBytes > maxBytes) {
            log.warn("Dropped held thinking blocks: limit={}", maxBytes)
            dropped = true
            held.clear()
            open.clear()
        }
        return !dropped
    }

    // Characters, which is near enough to bytes for a limit that only bounds what is held.
    private fun JsonValue.size(): Long =
        when (this) {
            is JsonValue.Text -> value.length.toLong()
            is JsonValue.Object -> members.values.sumOf { it.size() }
            is JsonValue.Array -> items.sumOf { it.size() }
            else -> 0
        }

    private class Block(
        text: String,
        signature: String,
    ) {
        val text = StringBuilder(text)
        val signature = StringBuilder(signature)

        fun toJsonValue(): JsonValue =
            JsonValue.Object(
                mapOf(
                    "type" to JsonValue.Text("thinking"),
                    "thinking" to JsonValue.Text(text.toString()),
                    "signature" to JsonValue.Text(signature.toString()),
                ),
            )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(HeldThinking::class.java)
    }
}
