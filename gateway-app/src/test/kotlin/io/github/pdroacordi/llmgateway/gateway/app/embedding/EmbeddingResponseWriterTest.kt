package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.embedding.Embedding
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingList
import io.github.pdroacordi.llmgateway.gateway.core.embedding.RawJson
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscription
import reactor.core.publisher.BaseSubscriber
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class EmbeddingResponseWriterTest {
    private val mapper = JsonMapper.builder().build()
    private val writer = EmbeddingResponseWriter(mapper)

    private fun bytes(
        list: EmbeddingList,
        usage: Usage = USAGE,
    ): ByteArray {
        val buffer = writer.write(list, usage)
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun text(
        list: EmbeddingList,
        usage: Usage = USAGE,
    ): String = bytes(list, usage).decodeToString()

    private fun vector(json: String): RawJson = RawJson(json.encodeToByteArray())

    private fun list(vararg items: Embedding): EmbeddingList = EmbeddingList(MODEL, items.toList())

    @Test
    fun `slices cover the reply in order, each at most 1 MiB, as views of the same array`() {
        val reply = ByteArray(SLICE * 2 + 5) { it.toByte() }

        val slices =
            writer
                .slices(ByteBuffer.wrap(reply))
                .collectList()
                .block()
                .orEmpty()

        assertEquals(listOf(SLICE, SLICE, 5), slices.map { it.remaining() })
        assertTrue(slices.all { it.hasArray() && it.array() === reply })
        val joined = ByteBuffer.allocate(reply.size)
        slices.forEach(joined::put)
        assertArrayEquals(reply, joined.array())
    }

    @Test
    fun `a view with an offset slices from its position`() {
        val reply = ByteBuffer.wrap("abcdef".encodeToByteArray(), 1, 4)

        val slice = writer.slices(reply).blockFirst()

        assertEquals("bcde", StandardCharsets.UTF_8.decode(checkNotNull(slice)).toString())
    }

    @Test
    fun `slices follow demand`() {
        val emitted = mutableListOf<ByteBuffer>()
        val subscriber =
            object : BaseSubscriber<ByteBuffer>() {
                override fun hookOnSubscribe(subscription: Subscription) = request(1)

                override fun hookOnNext(value: ByteBuffer) {
                    emitted += value
                }
            }

        writer.slices(ByteBuffer.wrap(ByteArray(SLICE * 3))).subscribe(subscriber)
        assertEquals(1, emitted.size)

        subscriber.request(1)
        assertEquals(2, emitted.size)
        subscriber.cancel()
    }

    @Test
    fun `the section 4 shape comes out member for member, with no completion_tokens`() {
        assertEquals(
            """{"object":"list","data":[{"object":"embedding","index":0,"embedding":"AAAA"}],""" +
                """"model":"m","usage":{"prompt_tokens":2,"total_tokens":2}}""",
            text(list(Embedding(0, vector("\"AAAA\"")))),
        )
    }

    @Test
    fun `items keep their order and index`() {
        val written = mapper.readTree(text(list(Embedding(1, vector("[1]")), Embedding(0, vector("[2]")))))

        assertEquals(1, written.at("/data/0/index").intValue())
        assertEquals(2, written.at("/data/1/embedding/0").intValue())
    }

    @Test
    fun `a vector's bytes are unchanged, literals, whitespace and non-ASCII included`() {
        val raw = "[ 1e-5 ,\n\t0.10,1E+2, 0.1000000000000000055511151231257827 , \"é\" ]".encodeToByteArray()

        val written = bytes(list(Embedding(0, RawJson(raw))))

        val prefix = """{"object":"list","data":[{"object":"embedding","index":0,"embedding":""".length
        assertArrayEquals(raw, written.copyOfRange(prefix, prefix + raw.size))
    }

    @Test
    fun `extras of an item and of the list follow the typed members, in order`() {
        val itemExtras = JsonValue.Object(linkedMapOf("x_item" to JsonValue.Number("1"), "y" to JsonValue.Null))
        val listExtras = JsonValue.Object(linkedMapOf("x_top" to JsonValue.Text("é"), "z" to JsonValue.Bool(true)))

        assertEquals(
            """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[1],"x_item":1,"y":null}],""" +
                """"model":"m","usage":{"prompt_tokens":2,"total_tokens":2},"x_top":"é","z":true}""",
            text(EmbeddingList(MODEL, listOf(Embedding(0, vector("[1]"), itemExtras)), listExtras)),
        )
    }

    @Test
    fun `usage extras are kept`() {
        val extras = JsonValue.Object(mapOf("x_usage" to JsonValue.Number("3")))
        val written = text(list(Embedding(0, vector("[1]"))), Usage(TokenCount(2), TokenCount(0), extras = extras))

        assertTrue(""""usage":{"prompt_tokens":2,"total_tokens":2,"x_usage":3}""" in written) { written }
    }

    @Test
    fun `an estimate has top-level llmgw, after the extras`() {
        val extras = JsonValue.Object(mapOf("x_top" to JsonValue.Number("1")))
        val usage = Usage(TokenCount(2), TokenCount(0), estimated = true)

        val written = text(EmbeddingList(MODEL, listOf(Embedding(0, vector("[1]"))), extras), usage)

        assertTrue(written.endsWith(""""x_top":1,"llmgw":{"usage_estimated":true}}""")) { written }
    }

    @Test
    fun `a body with provider usage has no llmgw`() {
        assertFalse("llmgw" in text(list(Embedding(0, vector("[1]")))))
    }

    @Test
    fun `a model name is escaped`() {
        val written = text(EmbeddingList(ModelName("a\"b"), listOf(Embedding(0, vector("[1]")))))

        assertEquals("a\"b", mapper.readTree(written).get("model").stringValue())
    }

    @Test
    fun `a body larger than the first guess is complete`() {
        val extras = JsonValue.Object(mapOf("x_top" to JsonValue.Text("a".repeat(100_000))))

        val written = mapper.readTree(text(EmbeddingList(MODEL, listOf(Embedding(0, vector("[1]"))), extras)))

        assertEquals(100_000, written.get("x_top").stringValue().length)
    }

    private companion object {
        const val SLICE = 1024 * 1024
        val MODEL = ModelName("m")
        val USAGE = Usage(TokenCount(2), TokenCount(0))
    }
}
