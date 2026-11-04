package io.github.pdroacordi.llmgateway.mockprovider.web

/** The fields of a provider error body; [param] is OpenAI's only, and [code] is `error_code` on Anthropic. */
data class ErrorBody(
    val status: Int,
    val type: String,
    val code: String?,
    val message: String,
    val param: String?,
)

/** One unit of a stream, the one `perTokenMs` and `afterPieces` count; each of its [frames] is flushed alone. */
data class Piece(
    val frames: List<String>,
) {
    constructor(frame: String) : this(listOf(frame))
}

/** A successful response, rendered by the route; `Serving` applies latency and faults to it. */
sealed interface Reply {
    /** [pieces] is the completion's piece count, which sets the non-stream wait (`perTokenMs` x pieces). */
    class Json(
        val body: String,
        val malformed: String,
        val pieces: Int,
    ) : Reply

    /**
     * SSE frames: [opening] (role chunk, or `message_start`..`ping`), one frame per piece, then [closing]. [malformed]
     * and [errorEvent] are the frames the `malformed` and `stream_error` faults send in the provider's framing.
     */
    class Stream(
        val opening: List<String>,
        val pieces: List<Piece>,
        val closing: List<String>,
        val malformed: String,
        val errorEvent: (ErrorBody) -> String,
    ) : Reply {
        fun concatenated(): String = (opening + pieces.flatMap { it.frames } + closing).joinToString("")
    }
}
