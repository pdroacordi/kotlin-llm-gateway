package io.github.pdroacordi.llmgateway.gateway.core.team

import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.error.Unauthenticated
import java.time.Clock

sealed interface Authentication {
    data class Authenticated(
        val team: TeamId,
        val key: TeamKeyId,
    ) : Authentication

    data class Rejected(
        val error: GatewayError,
    ) : Authentication
}

/** Team key to team. Revocation and expiry are judged here on every request, cached or not (ADR-GW-04-006). */
class TeamAuthenticator(
    private val hasher: KeyHasher,
    private val store: TeamStorePort,
    private val clock: Clock,
) {
    /** [authorization] is every value of the header. */
    suspend fun authenticate(authorization: List<String>): Authentication =
        when (val credential = BearerCredential.parse(authorization)) {
            BearerCredential.Missing -> Authentication.Rejected(Unauthenticated.MissingKey)
            BearerCredential.Ambiguous -> INVALID
            is BearerCredential.Token -> authenticate(credential.value)
        }

    private suspend fun authenticate(token: String): Authentication {
        if (!TeamKeyFormat.matches(token)) return INVALID
        return when (val found = store.findKey(hasher.digest(token))) {
            is StoreResult.Failed -> Authentication.Rejected(found.error)
            is StoreResult.Ok -> judge(found.value)
        }
    }

    private fun judge(credential: KeyCredential?): Authentication {
        val expiresAt = credential?.expiresAt
        return when {
            credential == null -> INVALID
            credential.revokedAt != null -> INVALID
            expiresAt != null && !clock.instant().isBefore(expiresAt) -> INVALID
            else -> Authentication.Authenticated(credential.team, credential.key)
        }
    }

    private companion object {
        val INVALID = Authentication.Rejected(Unauthenticated.InvalidKey)
    }
}
