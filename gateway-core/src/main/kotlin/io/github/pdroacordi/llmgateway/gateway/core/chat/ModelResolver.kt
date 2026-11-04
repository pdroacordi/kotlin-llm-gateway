package io.github.pdroacordi.llmgateway.gateway.core.chat

/** Pure, like routing (CLAUDE.md). RTE-01 replaces the implementation and adds the team. */
fun interface ModelResolver {
    fun resolve(requested: ModelName): Deployment
}

data class ModelPrefix(
    val prefix: String,
    val provider: ProviderId,
)

/** First matching prefix, else [default]; the deployment is named after its provider. RTE-01 replaces this. */
class PrefixModelResolver(
    private val prefixes: List<ModelPrefix>,
    private val default: ProviderId,
) : ModelResolver {
    override fun resolve(requested: ModelName): Deployment {
        val provider = prefixes.firstOrNull { requested.value.startsWith(it.prefix) }?.provider ?: default
        return Deployment(DeploymentId(provider.value), provider, requested)
    }
}
