package io.github.pdroacordi.llmgateway.gateway.app.auth

import io.github.pdroacordi.llmgateway.gateway.core.request.UuidV7Generator
import io.github.pdroacordi.llmgateway.gateway.core.team.KeyHasher
import io.github.pdroacordi.llmgateway.gateway.core.team.OperatorAuthenticator
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamAdminService
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamAuthenticator
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamStorePort
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.security.DrbgParameters
import java.security.SecureRandom
import java.time.Clock
import kotlin.random.asKotlinRandom

private const val KEY_STRENGTH_BITS = 256
private const val SERVER_ADDRESS = "server.address"

/** A DRBG of its own: the default instantiation is 128 bits and would cap a 256-bit key (ADR-GW-04-012). */
internal fun seededKeyDrbg(): SecureRandom {
    val parameters = DrbgParameters.instantiation(KEY_STRENGTH_BITS, DrbgParameters.Capability.RESEED_ONLY, null)
    val random = SecureRandom.getInstance("DRBG", parameters)
    // getInstance does not seed a DRBG; the first draw does. Here it runs at startup, off the event loop.
    random.nextBytes(ByteArray(1))
    return random
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties::class)
class AuthConfiguration {
    // A bean factory method, so a refused secret stops the context before the server binds.
    @Bean
    fun keyHasher(
        properties: AuthProperties,
        environment: Environment,
    ): KeyHasher {
        properties.validate()
        PublishedSecrets.check(properties, environment.getProperty(SERVER_ADDRESS))
        return KeyHasher(properties.hmacSecret.reveal().encodeToByteArray())
    }

    @Bean
    fun teamAuthenticator(
        hasher: KeyHasher,
        store: TeamStorePort,
        clock: Clock,
    ): TeamAuthenticator = TeamAuthenticator(hasher, store, clock)

    @Bean
    fun operatorAuthenticator(
        hasher: KeyHasher,
        properties: AuthProperties,
    ): OperatorAuthenticator = OperatorAuthenticator(hasher, properties.adminKey.reveal())

    @Bean
    fun authWebFilter(
        teams: TeamAuthenticator,
        operator: OperatorAuthenticator,
    ): AuthWebFilter = AuthWebFilter(teams, operator)

    @Bean
    fun teamAdminService(
        store: TeamStorePort,
        hasher: KeyHasher,
        clock: Clock,
    ): TeamAdminService {
        val random = seededKeyDrbg().asKotlinRandom()
        return TeamAdminService(store, hasher, UuidV7Generator(clock, random), random, clock)
    }
}
