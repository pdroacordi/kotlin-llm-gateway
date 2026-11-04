package io.github.pdroacordi.llmgateway.gateway.app.auth

import io.github.pdroacordi.llmgateway.gateway.core.team.TeamId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Exists only on an authenticated `/v1` request, so its presence is the proof (ADR-GW-04-009). */
class TeamContext(
    val team: TeamId,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TeamContext>
}
