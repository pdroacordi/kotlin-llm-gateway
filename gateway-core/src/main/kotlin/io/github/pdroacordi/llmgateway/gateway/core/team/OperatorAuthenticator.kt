package io.github.pdroacordi.llmgateway.gateway.core.team

import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.error.Unauthenticated
import java.security.MessageDigest

/** The one admin credential, from the environment and separate from team keys (ADR-GW-04-007). */
class OperatorAuthenticator(
    private val hasher: KeyHasher,
    adminKey: String,
) {
    private val expected = hasher.digest(adminKey).hex.encodeToByteArray()

    /** Null when accepted. Compares digests with `MessageDigest.isEqual`, so the length leaks nothing. */
    fun check(authorization: List<String>): GatewayError? =
        when (val credential = BearerCredential.parse(authorization)) {
            BearerCredential.Missing -> Unauthenticated.MissingKey
            BearerCredential.Ambiguous -> Unauthenticated.InvalidKey
            is BearerCredential.Token -> if (matches(credential.value)) null else Unauthenticated.InvalidKey
        }

    private fun matches(token: String): Boolean =
        MessageDigest.isEqual(expected, hasher.digest(token).hex.encodeToByteArray())
}
