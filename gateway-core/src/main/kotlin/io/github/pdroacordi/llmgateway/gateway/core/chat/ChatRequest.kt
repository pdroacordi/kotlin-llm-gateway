package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue

@JvmInline
value class ModelName(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A model name cannot be blank." }
    }
}

/** Not an enum: a role the gateway never heard of passes through. */
@JvmInline
value class Role(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A role cannot be blank." }
    }
}

/**
 * Typed members are those the gateway reads; [extras] holds the rest, verbatim (ADR-018). No `toString` in this file
 * prints text, so a logged object cannot carry a prompt (ADR-GW-01-001).
 */
data class ChatRequest(
    val model: ModelName,
    val messages: List<ChatMessage>,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
    val streamOptions: StreamOptions? = null,
) {
    init {
        require(messages.isNotEmpty()) { "A chat request needs at least one message." }
        require(extras.members.keys.none { it in RESERVED }) { "extras holds a typed member." }
    }

    override fun toString(): String =
        "ChatRequest(model=${model.value}, messages=${messages.size}, extras=${extras.members.size})"

    private companion object {
        val RESERVED = setOf("model", "messages", "stream", "stream_options")
    }
}

/** [extras]: `name`, `tool_calls`, `tool_call_id`, ... */
data class ChatMessage(
    val role: Role,
    val content: MessageContent?,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    override fun toString(): String = "ChatMessage(extras=${extras.members.size})"
}

sealed interface MessageContent {
    data class Text(
        val text: String,
    ) : MessageContent {
        override fun toString(): String = "MessageContent.Text(length=${text.length})"
    }

    data class Parts(
        val parts: List<ContentPart>,
    ) : MessageContent
}

sealed interface ContentPart {
    data class Text(
        val text: String,
        val extras: JsonValue.Object = JsonValue.Object.EMPTY,
    ) : ContentPart {
        override fun toString(): String = "ContentPart.Text(length=${text.length})"
    }

    /** Anything but a `{"type":"text"}` part: an image, audio, a file. */
    data class Opaque(
        val part: JsonValue,
    ) : ContentPart {
        override fun toString(): String = "ContentPart.Opaque"
    }
}
