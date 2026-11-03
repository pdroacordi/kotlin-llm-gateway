package io.github.pdroacordi.llmgateway.gateway.app.request

import io.github.pdroacordi.llmgateway.gateway.core.request.RequestIdGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.security.SecureRandom
import java.time.Clock
import kotlin.random.asKotlinRandom

@Configuration(proxyBeanMethods = false)
class RequestContextConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun requestIdGenerator(clock: Clock): RequestIdGenerator {
        val random = SecureRandom.getInstance("DRBG")
        // getInstance does not seed a DRBG; the first draw does. Draw here, at startup and off the event loop,
        // so later draws read no entropy (ADR-FND-04-002).
        random.nextBytes(ByteArray(1))
        return RequestIdGenerator(clock, random.asKotlinRandom())
    }

    @Bean
    fun requestContextWebFilter(ids: RequestIdGenerator): RequestContextWebFilter = RequestContextWebFilter(ids)
}
