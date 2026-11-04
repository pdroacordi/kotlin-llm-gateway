package io.github.pdroacordi.llmgateway.gateway.app.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** `llmgw.providers.default-provider`: where a model no prefix claims goes. RTE-01 replaces this. */
@ConfigurationProperties("llmgw.providers")
data class ProviderSelectionProperties(
    val defaultProvider: String,
) {
    /** At least one provider, and the default among them (ADR-GW-03-004). */
    fun validate(enabled: Set<String>) {
        check(enabled.isNotEmpty()) {
            "No provider is enabled: set llmgw.providers.openai.enabled or llmgw.providers.anthropic.enabled."
        }
        check(defaultProvider in enabled) { "llmgw.providers.default-provider must name an enabled provider." }
    }
}
