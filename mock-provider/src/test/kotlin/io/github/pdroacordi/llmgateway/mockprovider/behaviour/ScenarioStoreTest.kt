package io.github.pdroacordi.llmgateway.mockprovider.behaviour

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class ScenarioStoreTest {
    private val defaults =
        Behaviour(
            latency = Latency(ttftMs = 1, perTokenMs = 2, jitterMs = 3),
            content = ContentSpec(mode = ContentMode.LOREM, completionTokens = 16),
            usage = UsageSpec(omit = false, cachedTokens = 0, cacheCreationTokens = 0),
        )
    private val store = ScenarioStore(defaults)

    @Test
    fun `no scenario gives the configured defaults`() {
        val resolved = store.resolve("mock-chat")

        assertEquals(ResolvedLatency(1, 2, 3), resolved.latency)
        assertEquals(ResolvedContent(ContentMode.LOREM, null, 16), resolved.content)
        assertNull(resolved.error)
        assertNull(resolved.scenarioModel)
        assertNull(resolved.step)
    }

    @Test
    fun `steps are taken in order, each times often, then otherwise`() {
        store.put(
            "m",
            Scenario(
                steps =
                    listOf(
                        Step(Behaviour(error = error(503)), times = 2),
                        Step(Behaviour(latency = Latency(ttftMs = 800))),
                    ),
                otherwise = Behaviour(latency = Latency(ttftMs = 20)),
            ),
        )

        val taken = List(5) { store.resolve("m") }

        assertEquals(listOf(503, 503, null, null, null), taken.map { it.error?.status })
        assertEquals(listOf(0, 0, 1, null, null), taken.map { it.step })
        assertEquals(listOf(20L, 20L, 800L, 20L, 20L), taken.map { it.latency.ttftMs })
        assertEquals(listOf("m"), taken.map { it.scenarioModel }.distinct())
        assertEquals(3L, store.snapshot().single().stepsConsumed)
    }

    @Test
    fun `the star scenario serves every model without its own`() {
        store.put("*", Scenario(steps = listOf(Step(Behaviour(error = error(429)))), otherwise = null))
        store.put("own", Scenario(steps = emptyList(), otherwise = Behaviour(latency = Latency(ttftMs = 5))))

        assertEquals(429, store.resolve("other").error?.status)
        assertEquals("*", store.resolve("other").scenarioModel)
        assertNull(store.resolve("other").error)
        assertNull(store.resolve("own").error)
        assertEquals("*", store.resolve(null).scenarioModel)
    }

    @Test
    fun `members inherit from the scenario otherwise, then the star otherwise, then the defaults`() {
        store.put(
            "*",
            Scenario(
                steps = emptyList(),
                otherwise = Behaviour(latency = Latency(perTokenMs = 50), content = ContentSpec(completionTokens = 4)),
            ),
        )
        store.put(
            "m",
            Scenario(
                steps = listOf(Step(Behaviour(latency = Latency(ttftMs = 800)))),
                otherwise = Behaviour(latency = Latency(jitterMs = 9), usage = UsageSpec(omit = true)),
            ),
        )

        val resolved = store.resolve("m")

        assertEquals(ResolvedLatency(800, 50, 9), resolved.latency)
        assertEquals(4, resolved.content.completionTokens)
        assertEquals(ContentMode.LOREM, resolved.content.mode)
        assertEquals(true, resolved.usage.omit)
    }

    @Test
    fun `error and fault are inherited together from the most specific level that sets either`() {
        store.put(
            "m",
            Scenario(
                steps = listOf(Step(Behaviour(fault = Fault(FaultKind.HANG)))),
                otherwise = Behaviour(error = error(500)),
            ),
        )

        val step = store.resolve("m")
        val otherwise = store.resolve("m")

        assertEquals(FaultKind.HANG, step.fault?.kind)
        assertNull(step.error)
        assertEquals(500, otherwise.error?.status)
        assertNull(otherwise.fault)
    }

    @Test
    fun `step boundaries follow times exactly`() {
        store.put(
            "m",
            Scenario(
                steps =
                    listOf(
                        Step(Behaviour(), times = 2),
                        Step(Behaviour(), times = 3),
                        Step(Behaviour(), times = 1),
                    ),
                otherwise = null,
            ),
        )

        assertEquals(listOf(0, 0, 1, 1, 1, 2, null, null), List(8) { store.resolve("m").step })
        assertEquals(6L, store.snapshot().single().stepsConsumed)
    }

    @Test
    fun `huge times are not expanded into memory, and totals beyond Int are counted`() {
        val huge = Step(Behaviour(error = error(503)), times = Int.MAX_VALUE)
        store.put("m", Scenario(steps = listOf(huge, huge), otherwise = null))

        assertEquals(503, store.resolve("m").error?.status)
        assertEquals(0, store.resolve("m").step)
        assertEquals(2L, store.snapshot().single().stepsConsumed)
    }

    @Test
    fun `put replaces the scenario and restarts its cursor`() {
        val scenario = Scenario(steps = listOf(Step(Behaviour(error = error(503)))), otherwise = null)
        store.put("m", scenario)
        store.resolve("m")
        store.put("m", scenario)

        assertEquals(503, store.resolve("m").error?.status)
    }

    @Test
    fun `remove and clear`() {
        store.put("a", Scenario(emptyList(), null))
        store.put("b", Scenario(emptyList(), null))
        store.remove("a")
        store.remove("absent")

        assertEquals(listOf("b"), store.snapshot().map { it.model })
        store.clear()
        assertEquals(emptyList<ScenarioState>(), store.snapshot())
    }

    @Test
    fun `under concurrency exactly times requests get a step`() {
        store.put("m", Scenario(steps = listOf(Step(Behaviour(error = error(503)), times = 37)), otherwise = null))

        val errors =
            Executors.newFixedThreadPool(8).asCoroutineDispatcher().use { threads ->
                runBlocking(threads) {
                    List(1000) { async { store.resolve("m").error } }.awaitAll()
                }
            }

        assertEquals(37, errors.count { it != null })
        assertEquals(37L, store.snapshot().single().stepsConsumed)
    }

    private fun error(status: Int) = InjectedError(status = status)
}
