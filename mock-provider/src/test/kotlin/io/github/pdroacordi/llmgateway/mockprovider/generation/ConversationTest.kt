package io.github.pdroacordi.llmgateway.mockprovider.generation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConversationTest {
    @Test
    fun `OpenAI string, null and text-parts content`() {
        assertEquals(Turn("user", "hello"), Turn.openAi("user", "hello"))
        assertEquals(Turn("assistant", ""), Turn.openAi("assistant", null))
        val parts = listOf(ContentPart.Text("a"), ContentPart.Other, ContentPart.Text("b"))
        assertEquals(Turn("user", "a\nb"), Turn.openAi("user", parts))
    }

    @Test
    fun `Anthropic blocks keep text and the text inside tool_result blocks`() {
        val blocks =
            listOf(
                ContentPart.Text("look"),
                ContentPart.ToolResult(listOf(ContentPart.Text("42"), ContentPart.Other)),
                ContentPart.Other,
            )
        assertEquals(Turn("user", "look\n42"), Turn.anthropic("user", blocks))
        assertEquals(Turn("system", "be brief"), Turn.anthropic("system", listOf(ContentPart.Text("be brief"))))
    }

    @Test
    fun `the canonical string separates turns`() {
        val one = Conversation("m", listOf(Turn("user", "ab"), Turn("user", "c")))
        val two = Conversation("m", listOf(Turn("user", "a"), Turn("user", "bc")))
        assertNotEquals(one.canonical(), two.canonical())
        assertEquals("m\u0000user\u0000ab\u0000user\u0000c", one.canonical())
    }

    @Test
    fun `the model is part of the canonical string`() {
        val turns = listOf(Turn("user", "x"))
        assertNotEquals(Conversation("a", turns).seed(), Conversation("b", turns).seed())
        assertEquals(Conversation("a", turns).seed(), Conversation("a", turns).seed())
    }

    @Test
    fun `the last user turn`() {
        val conversation =
            Conversation("m", listOf(Turn("user", "first"), Turn("assistant", "reply"), Turn("user", "second")))
        assertEquals("second", conversation.lastUserText())
        assertNull(Conversation("m", listOf(Turn("system", "s"))).lastUserText())
    }
}
