package io.github.pdroacordi.llmgateway.mockprovider.generation

import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ContentMode
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ResolvedContent
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ResolvedUsage
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ScriptedFinish
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ThinkingSpec
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ToolCallSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompletionPlannerTest {
    private val planner = CompletionPlanner(TextGenerator())
    private val conversation =
        Conversation(
            "mock-chat",
            listOf(Turn("system", "Be brief."), Turn("user", "Please greet the mock provider in two words")),
        )
    private val lorem = ResolvedContent(ContentMode.LOREM, null, 16)
    private val noUsageOverride = ResolvedUsage(omit = false, cachedTokens = 0, cacheCreationTokens = 0)

    @Test
    fun `lorem gives the configured number of tokens and finishes with stop`() {
        val plan = planner.plan(conversation, null, lorem, noUsageOverride)

        assertEquals(16, plan.pieces.size)
        assertEquals(plan.text, plan.pieces.joinToString(""))
        assertEquals(Finish.STOP, plan.finish)
        assertEquals(16, plan.usage.completionTokens)
    }

    @Test
    fun `lorem is deterministic per conversation and changes with it`() {
        val again = planner.plan(conversation, null, lorem, noUsageOverride)
        val other =
            planner.plan(
                conversation.copy(turns = listOf(Turn("user", "Please greet the mock provider in two words!"))),
                null,
                lorem,
                noUsageOverride,
            )

        assertEquals(planner.plan(conversation, null, lorem, noUsageOverride), again)
        assertTrue(again.text != other.text)
    }

    @Test
    fun `echo repeats the last user turn and fixed returns the scenario text`() {
        val echo = planner.plan(conversation, null, ResolvedContent(ContentMode.ECHO, null, 16), noUsageOverride)
        val fixed =
            planner.plan(conversation, null, ResolvedContent(ContentMode.FIXED, "Hi there", 16), noUsageOverride)

        assertEquals("Please greet the mock provider in two words", echo.text)
        assertEquals(8, echo.pieces.size)
        assertEquals(listOf("Hi", " there"), fixed.pieces)
    }

    @Test
    fun `a cap cuts the content in pieces and sets length`() {
        val plan = planner.plan(conversation, 3, lorem, noUsageOverride)

        assertEquals(3, plan.pieces.size)
        assertEquals(Finish.LENGTH, plan.finish)
        assertEquals(3, plan.usage.completionTokens)
    }

    @Test
    fun `a cap equal to the length does not cut`() {
        val plan = planner.plan(conversation, 2, ResolvedContent(ContentMode.FIXED, "Hi there", 16), noUsageOverride)

        assertEquals(Finish.STOP, plan.finish)
    }

    @Test
    fun `max_tokens 0 gives empty content and length`() {
        val plan = planner.plan(conversation, 0, lorem, noUsageOverride)

        assertEquals("", plan.text)
        assertEquals(Finish.LENGTH, plan.finish)
        assertEquals(0, plan.usage.completionTokens)
    }

    @Test
    fun `prompt tokens are 3 per turn plus its tokens, plus 3`() {
        // system "Be brief." = 3 tokens, user = 8 tokens: (3 + 3) + (3 + 8) + 3.
        val plan = planner.plan(conversation, null, lorem, noUsageOverride)

        assertEquals(20, plan.usage.promptTokens)
        assertEquals(20, PromptTokens.of(conversation))
    }

    @Test
    fun `overrides replace the reported counts, not the content`() {
        val usage = noUsageOverride.copy(promptTokens = 1000, completionTokens = 7)
        val plan = planner.plan(conversation, null, lorem, usage)

        assertEquals(1000, plan.usage.promptTokens)
        assertEquals(7, plan.usage.completionTokens)
        assertEquals(16, plan.pieces.size)
    }

    @Test
    fun `cache counts are clamped to the prompt`() {
        val clamped =
            planner.plan(
                conversation,
                null,
                lorem,
                noUsageOverride.copy(cachedTokens = 15, cacheCreationTokens = 9),
            )
        val all =
            planner.plan(
                conversation,
                null,
                lorem,
                noUsageOverride.copy(cachedTokens = 50, cacheCreationTokens = 9),
            )

        assertEquals(15, clamped.usage.cachedTokens)
        assertEquals(5, clamped.usage.cacheCreationTokens)
        assertEquals(0, clamped.usage.anthropicInputTokens)
        assertEquals(20, all.usage.cachedTokens)
        assertEquals(0, all.usage.cacheCreationTokens)
    }

    @Test
    fun `the Anthropic input split always sums to the OpenAI prompt count`() {
        val usage =
            planner
                .plan(
                    conversation,
                    null,
                    lorem,
                    noUsageOverride.copy(cachedTokens = 4, cacheCreationTokens = 6),
                ).usage

        assertEquals(10, usage.anthropicInputTokens)
        assertEquals(usage.promptTokens, usage.anthropicInputTokens + usage.cachedTokens + usage.cacheCreationTokens)
        assertEquals(usage.promptTokens + usage.completionTokens, usage.totalTokens)
    }

    @Test
    fun `omit is carried to the plan`() {
        assertTrue(planner.plan(conversation, null, lorem, noUsageOverride.copy(omit = true)).usage.omit)
        assertFalse(planner.plan(conversation, null, lorem, noUsageOverride).usage.omit)
    }

    private val tool = ToolCallSpec("t1", "get", """{"city":"Paris"}""", 8)

    private fun fixed(
        text: String,
        toolCalls: List<ToolCallSpec> = emptyList(),
        finish: ScriptedFinish? = null,
        thinking: List<ThinkingSpec> = emptyList(),
    ) = ResolvedContent(ContentMode.FIXED, text, 16, toolCalls, finish, thinking)

    @Test
    fun `parts come in order, thinking then text then tools, and text and pieces are derived`() {
        val thinking = listOf(ThinkingSpec(signature = "s"))
        val plan = planner.plan(conversation, null, fixed("Hi there", listOf(tool), null, thinking), noUsageOverride)

        assertEquals(
            listOf(PlanPart.Thinking::class, PlanPart.Text::class, PlanPart.ToolCall::class),
            plan.parts.map { it::class },
        )
        assertEquals("Hi there", plan.text)
        assertEquals(listOf("Hi", " there", """{"city":""", """"Paris"}"""), plan.pieces)
    }

    @Test
    fun `completion tokens count text and argument pieces, not thinking`() {
        val thinking = listOf(ThinkingSpec(signature = "s"))
        val plan = planner.plan(conversation, null, fixed("Hi there", listOf(tool), null, thinking), noUsageOverride)

        assertEquals(4, plan.usage.completionTokens)
    }

    @Test
    fun `tool calls imply the tool finish, and a scripted finish wins`() {
        val plain = planner.plan(conversation, null, fixed("", listOf(tool)), noUsageOverride)
        val scripted = fixed("", listOf(tool), ScriptedFinish.REFUSAL)

        assertEquals(Finish.TOOL_CALLS, plain.finish)
        assertEquals(Finish.REFUSAL, planner.plan(conversation, null, scripted, noUsageOverride).finish)
    }

    @Test
    fun `a call the cap would cut is left out, with a length finish`() {
        val plan = planner.plan(conversation, 3, fixed("Hi there", listOf(tool)), noUsageOverride)

        assertEquals(emptyList<PlanPart.ToolCall>(), plan.toolCalls)
        assertEquals(Finish.LENGTH, plan.finish)
    }

    @Test
    fun `an empty input has no pieces, and no text means no text part beside a tool call`() {
        val plan = planner.plan(conversation, null, fixed("", listOf(ToolCallSpec(null, "now", "{}"))), noUsageOverride)

        assertEquals(1, plan.parts.size)
        assertEquals(emptyList<String>(), plan.toolCalls.single().pieces)
    }
}
