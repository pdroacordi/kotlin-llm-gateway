package io.github.pdroacordi.llmgateway.gateway.app.security

import org.springframework.boot.context.properties.ConfigurationProperties

/** [allowUnauthenticatedExposure] goes with GW-04 (ADR-GW-01-007). */
@ConfigurationProperties("llmgw.security")
data class SecurityProperties(
    val allowUnauthenticatedExposure: Boolean = false,
)
