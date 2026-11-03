package io.github.pdroacordi.llmgateway.mockprovider

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The golden files' clock: `created` = 1793750400. */
@TestConfiguration(proxyBeanMethods = false)
class FixedClockConfiguration {
    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochSecond(Golden.CREATED), ZoneOffset.UTC)
}
