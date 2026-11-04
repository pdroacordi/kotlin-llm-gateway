package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.core.team.KeyDigest
import io.github.pdroacordi.llmgateway.gateway.core.team.StoreResult
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamStorePort
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * One good lookup after Flyway and before the server accepts requests (GW-04 spike S6), so the driver's classes load
 * and the pool connects here, off the event loop. The digest belongs to no key: the query is the point, not its answer.
 * [deadline] covers every attempt, and is not `store-timeout`.
 */
class TeamStoreWarmup(
    private val store: TeamStorePort,
    private val deadline: Duration,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        // The one runBlocking in main, a startup-only exception: the server must not bind before this ends (GW-04 Log).
        runBlocking { warm() }
    }

    internal suspend fun warm() {
        val warmed =
            withTimeoutOrNull(deadline) {
                // Repeated: a cold connect outlasts max-acquire-time, and a cold first query store-timeout.
                while (store.findKey(NO_KEY) is StoreResult.Failed) delay(RETRY_PAUSE)
            }
        // Flyway has just reached the database, so this is a blip, and the first lookups pay for the warm-up instead.
        if (warmed == null) log.warn("Team store warm-up failed")
    }

    private companion object {
        val NO_KEY = KeyDigest("0".repeat(64))
        val RETRY_PAUSE = 100.milliseconds
        val log: Logger = LoggerFactory.getLogger(TeamStoreWarmup::class.java)
    }
}
