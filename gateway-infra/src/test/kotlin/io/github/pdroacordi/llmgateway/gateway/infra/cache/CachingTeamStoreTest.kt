package io.github.pdroacordi.llmgateway.gateway.infra.cache

import io.github.pdroacordi.llmgateway.gateway.core.error.TeamStoreFailure
import io.github.pdroacordi.llmgateway.gateway.core.team.InMemoryTeamStore
import io.github.pdroacordi.llmgateway.gateway.core.team.KeyDigest
import io.github.pdroacordi.llmgateway.gateway.core.team.NewTeamKey
import io.github.pdroacordi.llmgateway.gateway.core.team.PageRequest
import io.github.pdroacordi.llmgateway.gateway.core.team.ResourceName
import io.github.pdroacordi.llmgateway.gateway.core.team.StoreResult
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamKey
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamKeyId
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class CachingTeamStoreTest {
    private val delegate = InMemoryTeamStore()
    private val hasher = TestKeys.hasher()
    private val digest = hasher.digest(TestKeys.TEAM_KEY)
    private val unknown = hasher.digest(TestKeys.UNKNOWN_KEY)
    private val nanos = AtomicLong()
    private val store = cached()

    private fun cached(
        maxSize: Long = 100,
        negativeMaxSize: Long = 100,
    ): CachingTeamStore =
        CachingTeamStore(delegate, TeamCacheConfig(TTL, maxSize, NEGATIVE_TTL, negativeMaxSize), nanos::get)

    private fun advance(duration: kotlin.time.Duration) {
        nanos.addAndGet(duration.inWholeNanoseconds)
    }

    private fun <T> StoreResult<T>.ok(): T = (this as StoreResult.Ok).value

    @BeforeEach
    fun seed() {
        delegate.seed(hasher)
    }

    @Test
    fun `a second lookup is a hit, with no store call`() =
        runTest {
            val first = store.findKey(digest)
            val second = store.findKey(digest)

            assertEquals(TestKeys.KEY_ID, first.ok()?.key)
            assertEquals(first, second)
            assertEquals(1, delegate.findKeyCalls)
        }

    @Test
    fun `an entry is read again once its TTL ends`() =
        runTest {
            store.findKey(digest)
            advance(TTL - 1.seconds)
            store.findKey(digest)
            assertEquals(1, delegate.findKeyCalls)

            advance(1.seconds)
            store.findKey(digest)

            assertEquals(2, delegate.findKeyCalls)
        }

    @Test
    fun `a hit does not extend the TTL`() =
        runTest {
            store.findKey(digest)
            advance(TTL - 1.seconds)
            store.findKey(digest)
            advance(2.seconds)

            store.findKey(digest)

            assertEquals(2, delegate.findKeyCalls)
        }

    @Test
    fun `the cache holds at most max-size entries`() =
        runTest {
            val small = cached(maxSize = 2)
            val digests = (0 until 5).map { key(small, "bounded-$it") }

            digests.forEach { small.findKey(it) }
            small.cleanUp()

            assertTrue(small.cachedKeys <= 2) { "${small.cachedKeys} entries" }
        }

    @Test
    fun `an unknown digest is remembered, with no second store call`() =
        runTest {
            assertNull(store.findKey(unknown).ok())
            assertNull(store.findKey(unknown).ok())

            assertEquals(1, delegate.findKeyCalls)
        }

    @Test
    fun `an unknown digest is forgotten after the negative TTL, shorter than the positive one`() =
        runTest {
            store.findKey(digest)
            store.findKey(unknown)
            advance(NEGATIVE_TTL)

            store.findKey(unknown)
            store.findKey(digest)

            assertEquals(3, delegate.findKeyCalls)
        }

    @Test
    fun `random tokens cannot evict good entries, because the two caches are separate`() =
        runTest {
            val small = cached(maxSize = 10, negativeMaxSize = 1)
            small.findKey(digest)

            repeat(50) { small.findKey(hasher.digest("random-$it")) }
            small.cleanUp()
            val calls = delegate.findKeyCalls
            small.findKey(digest)

            assertEquals(calls, delegate.findKeyCalls)
        }

    @Test
    fun `a failure is not cached`() =
        runTest {
            delegate.available = false
            assertEquals(StoreResult.Failed(TeamStoreFailure.Unavailable), store.findKey(digest))
            delegate.available = true

            assertEquals(TestKeys.KEY_ID, store.findKey(digest).ok()?.key)
        }

    @Test
    fun `a hit is served while the delegate is down`() =
        runTest {
            store.findKey(digest)
            delegate.available = false

            assertEquals(TestKeys.KEY_ID, store.findKey(digest).ok()?.key)
        }

    @Test
    fun `an entry past its TTL is not served while the delegate is down`() =
        runTest {
            store.findKey(digest)
            delegate.available = false
            advance(TTL)

            assertEquals(StoreResult.Failed(TeamStoreFailure.Unavailable), store.findKey(digest))
        }

    @Test
    fun `a revoke drops the entry, so the next lookup sees revokedAt at once`() =
        runTest {
            assertNull(store.findKey(digest).ok()?.revokedAt)

            store.revokeKey(TestKeys.TEAM_ID, TestKeys.KEY_ID, NOW)

            assertEquals(NOW, store.findKey(digest).ok()?.revokedAt)
        }

    @Test
    fun `a revoked row is cached like any other, for core to refuse`() =
        runTest {
            store.revokeKey(TestKeys.TEAM_ID, TestKeys.KEY_ID, NOW)
            store.findKey(digest)
            val calls = delegate.findKeyCalls

            assertNotNull(store.findKey(digest).ok()?.revokedAt)
            assertEquals(calls, delegate.findKeyCalls)
        }

    @Test
    fun `a failed revoke drops nothing`() =
        runTest {
            store.findKey(digest)
            val other = TeamKeyId(Uuid.parse("01900000-0000-7000-8000-0000000000ff"))

            store.revokeKey(TestKeys.TEAM_ID, other, NOW)
            store.findKey(digest)

            assertEquals(1, delegate.findKeyCalls)
        }

    @Test
    fun `a create drops the negative entry of its digest`() =
        runTest {
            val digest = hasher.digest("created-later")
            assertNull(store.findKey(digest).ok())

            store.createKey(newKey("created-later", digest))

            assertNotNull(store.findKey(digest).ok())
        }

    @Test
    fun `the other operations reach the delegate`() =
        runTest {
            assertEquals(
                listOf(TestKeys.KEY_ID),
                store
                    .listKeys(TestKeys.TEAM_ID, PageRequest())
                    .ok()
                    .items
                    .map { it.id },
            )
            assertEquals(
                listOf(TestKeys.TEAM_ID),
                store
                    .listTeams(PageRequest())
                    .ok()
                    .items
                    .map { it.id },
            )
        }

    // A full write buffer makes a writer take the eviction lock itself, which parks if another thread holds it.
    @Test
    fun `concurrent writes on non-blocking threads pass BlockHound`() {
        val small = cached(maxSize = 16, negativeMaxSize = 16)

        val results =
            Flux
                .range(0, WRITES)
                .flatMap({ mono { small.findKey(hasher.digest("load-$it")) }.subscribeOn(Schedulers.parallel()) }, 64)
                .collectList()
                .block(Duration.ofSeconds(60))
                .orEmpty()

        assertEquals(WRITES, results.size)
    }

    private suspend fun key(
        store: CachingTeamStore,
        name: String,
    ): KeyDigest {
        val digest = hasher.digest(name)
        store.createKey(newKey(name, digest))
        return digest
    }

    private fun newKey(
        name: String,
        digest: KeyDigest,
    ): NewTeamKey {
        val id = TeamKeyId(Uuid.random())
        val key = TeamKey(id, TestKeys.TEAM_ID, checkNotNull(ResourceName.parseOrNull(name)), "h", NOW, null, null)
        return NewTeamKey(key, digest, 1)
    }

    private companion object {
        val TTL = 30.seconds
        val NEGATIVE_TTL = 5.seconds
        val NOW: Instant = Instant.parse("2026-11-04T12:00:00Z")
        const val WRITES = 200_000
    }
}
