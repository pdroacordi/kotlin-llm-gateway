package io.github.pdroacordi.llmgateway.gateway.core.chat

@JvmInline
value class DeploymentId(
    val value: String,
)

@JvmInline
value class ProviderId(
    val value: String,
)

data class Deployment(
    val id: DeploymentId,
    val provider: ProviderId,
    val model: ModelName,
)
