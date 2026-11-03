package io.github.pdroacordi.llmgateway.mockprovider.web

import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ScenarioStore
import io.github.pdroacordi.llmgateway.mockprovider.generation.CompletionPlanner
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** Collaborators every provider route uses. */
class Generation(
    val mapper: JsonMapper,
    val clock: Clock,
    val store: ScenarioStore,
    val planner: CompletionPlanner,
    val serving: Serving,
    val credentials: Credentials,
)
