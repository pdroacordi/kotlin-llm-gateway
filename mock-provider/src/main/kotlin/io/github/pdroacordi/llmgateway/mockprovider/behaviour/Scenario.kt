package io.github.pdroacordi.llmgateway.mockprovider.behaviour

/** [times] >= 1: how many requests take this step before the next one. */
data class Step(
    val behaviour: Behaviour,
    val times: Int = 1,
) {
    init {
        require(times >= 1) { "times must be at least 1" }
    }
}

/** Per-model ordered steps, consumed one per request in arrival order, then [otherwise] (ADR-FND-03-004). */
data class Scenario(
    val steps: List<Step>,
    val otherwise: Behaviour?,
)

/** A stored scenario as `GET /__mock/scenarios` shows it. */
data class ScenarioState(
    val model: String,
    val scenario: Scenario,
    /** Requests that took a step; at most the sum of `times`. */
    val stepsConsumed: Long,
)
