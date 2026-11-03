package io.github.pdroacordi.llmgateway.mockprovider.behaviour

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Scenarios by model. One atomic cursor update per request, no lock: exactly `times` requests get a step under any
 * concurrency (FND-03 section 5, Data). A request keeps the behaviour it resolved, whatever admin calls follow.
 */
class ScenarioStore(
    private val defaults: Behaviour,
) {
    private val scenarios = ConcurrentHashMap<String, Entry>()

    fun put(
        model: String,
        scenario: Scenario,
    ) {
        scenarios[model] = Entry(scenario)
    }

    fun remove(model: String) {
        scenarios.remove(model)
    }

    fun clear() {
        scenarios.clear()
    }

    fun resolve(model: String?): ResolvedBehaviour {
        val own = model?.let { scenarios[it] }
        val star = scenarios[STAR]
        val (key, entry) =
            when {
                own != null -> model to own
                star != null -> STAR to star
                else -> null to null
            }
        val taken = entry?.take()
        val levels =
            listOfNotNull(
                taken?.behaviour ?: entry?.scenario?.otherwise,
                entry?.scenario?.otherwise.takeIf { taken != null },
                star?.scenario?.otherwise.takeIf { entry !== star },
                defaults,
            )
        return merge(levels, key, taken?.index)
    }

    fun snapshot(): List<ScenarioState> =
        scenarios.entries
            .sortedBy { it.key }
            .map { (model, entry) -> ScenarioState(model, entry.scenario, entry.consumed()) }

    private fun merge(
        levels: List<Behaviour>,
        scenarioModel: String?,
        step: Int?,
    ): ResolvedBehaviour {
        // `error` and `fault` travel together: a level that sets either decides both, so a step's fault is never
        // combined with an error inherited from `otherwise`.
        val outcome = levels.firstOrNull { it.error != null || it.fault != null }
        return ResolvedBehaviour(
            latency =
                ResolvedLatency(
                    ttftMs = levels.firstOf { it.latency?.ttftMs } ?: 0,
                    perTokenMs = levels.firstOf { it.latency?.perTokenMs } ?: 0,
                    jitterMs = levels.firstOf { it.latency?.jitterMs } ?: 0,
                ),
            error = outcome?.error,
            fault = outcome?.fault,
            content =
                ResolvedContent(
                    mode = levels.firstOf { it.content?.mode } ?: ContentMode.LOREM,
                    text = levels.firstOf { it.content?.text },
                    completionTokens = levels.firstOf { it.content?.completionTokens } ?: DEFAULT_COMPLETION_TOKENS,
                ),
            usage =
                ResolvedUsage(
                    omit = levels.firstOf { it.usage?.omit } ?: false,
                    cachedTokens = levels.firstOf { it.usage?.cachedTokens } ?: 0,
                    cacheCreationTokens = levels.firstOf { it.usage?.cacheCreationTokens } ?: 0,
                    promptTokens = levels.firstOf { it.usage?.promptTokens },
                    completionTokens = levels.firstOf { it.usage?.completionTokens },
                ),
            scenarioModel = scenarioModel,
            step = step,
        )
    }

    private fun <T : Any> List<Behaviour>.firstOf(field: (Behaviour) -> T?): T? = firstNotNullOfOrNull(field)

    private class Taken(
        val index: Int,
        val behaviour: Behaviour,
    )

    private class Entry(
        val scenario: Scenario,
    ) {
        // Steps are never expanded by `times`: ends[i] is the number of requests steps 0..i serve in total.
        private val ends: LongArray =
            scenario.steps
                .runningFold(0L) { total, step -> total + step.times }
                .drop(1)
                .toLongArray()
        private val total: Long = ends.lastOrNull() ?: 0
        private val cursor = AtomicLong(0)

        fun take(): Taken? {
            // Capped at the total, so the cursor never overflows and equals the requests that took a step.
            val position = cursor.getAndUpdate { if (it < total) it + 1 else it }
            if (position >= total) return null
            // The step whose range holds position: the first i with ends[i] > position.
            val found = ends.binarySearch(position)
            val index = if (found >= 0) found + 1 else -found - 1
            return Taken(index, scenario.steps[index].behaviour)
        }

        fun consumed(): Long = cursor.get()
    }

    companion object {
        const val STAR = "*"
        private const val DEFAULT_COMPLETION_TOKENS = 16
    }
}
