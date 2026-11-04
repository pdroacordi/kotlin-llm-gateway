package io.github.pdroacordi.llmgateway.gateway.core.chat

/** Pure, like routing (CLAUDE.md). RTE-01 replaces the implementation and adds the team. */
fun interface ModelResolver {
    fun resolve(requested: ModelName): Deployment
}

/** Every model goes to the one deployment under the client's name (ADR-GW-01-006). */
class PassThroughModelResolver(
    private val id: DeploymentId,
    private val provider: ProviderId,
) : ModelResolver {
    override fun resolve(requested: ModelName): Deployment = Deployment(id, provider, requested)
}
