package io.github.pdroacordi.llmgateway.gateway.infra.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.pdroacordi.llmgateway.gateway.core.team.KeyCredential
import io.github.pdroacordi.llmgateway.gateway.core.team.KeyDigest
import io.github.pdroacordi.llmgateway.gateway.core.team.NewTeamKey
import io.github.pdroacordi.llmgateway.gateway.core.team.RevokedKey
import io.github.pdroacordi.llmgateway.gateway.core.team.StoreResult
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamId
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamKey
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamKeyId
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamStorePort
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration

data class TeamCacheConfig(
    val ttl: Duration,
    val maxSize: Long,
    val negativeTtl: Duration,
    val negativeMaxSize: Long,
)

/**
 * The only user of Caffeine (ADR-GW-04-006). Two caches, so a flood of random tokens cannot evict good entries. Revoked
 * rows are cached too: expiry and revocation are judged by `TeamAuthenticator`, so no entry needs a clock of its own.
 * [nanos] is the time source of the TTLs.
 */
class CachingTeamStore(
    private val delegate: TeamStorePort,
    config: TeamCacheConfig,
    nanos: () -> Long = System::nanoTime,
) : TeamStorePort by delegate {
    private val known: Cache<KeyDigest, KeyCredential> = cache(config.ttl, config.maxSize, nanos)
    private val unknown: Cache<KeyDigest, Unit> = cache(config.negativeTtl, config.negativeMaxSize, nanos)

    internal val cachedKeys: Long get() = known.estimatedSize()

    internal fun cleanUp() {
        known.cleanUp()
        unknown.cleanUp()
    }

    override suspend fun findKey(digest: KeyDigest): StoreResult<KeyCredential?> {
        val hit = known.getIfPresent(digest)
        return when {
            hit != null -> StoreResult.Ok(hit)
            unknown.getIfPresent(digest) != null -> StoreResult.Ok(null)
            else -> delegate.findKey(digest).also { if (it is StoreResult.Ok) remember(digest, it.value) }
        }
    }

    private fun remember(
        digest: KeyDigest,
        credential: KeyCredential?,
    ) {
        if (credential == null) unknown.put(digest, Unit) else known.put(digest, credential)
    }

    override suspend fun createKey(key: NewTeamKey): StoreResult<TeamKey> =
        delegate.createKey(key).also { if (it is StoreResult.Ok) unknown.invalidate(key.digest) }

    override suspend fun revokeKey(
        team: TeamId,
        key: TeamKeyId,
        at: Instant,
    ): StoreResult<RevokedKey> =
        delegate.revokeKey(team, key, at).also { if (it is StoreResult.Ok) known.invalidate(it.value.digest) }

    private fun <V : Any> cache(
        ttl: Duration,
        maxSize: Long,
        nanos: () -> Long,
    ): Cache<KeyDigest, V> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(ttl.toJavaDuration())
            .maximumSize(maxSize)
            .ticker(nanos)
            .build()
}
