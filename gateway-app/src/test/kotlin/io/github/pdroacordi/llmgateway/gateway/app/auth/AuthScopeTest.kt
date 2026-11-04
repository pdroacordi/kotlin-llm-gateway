package io.github.pdroacordi.llmgateway.gateway.app.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.RequestPredicate
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.result.method.RequestMappingInfoHandlerMapping
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono
import java.util.Optional
import java.util.function.Consumer
import java.util.function.Function

/** D's safety in ADR-GW-04-003: a route outside the scope fails the build. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthScopeTest(
    @Autowired private val context: ApplicationContext,
) {
    private class PathCollector : RouterFunctions.Visitor {
        val paths = mutableListOf<String>()

        override fun startNested(predicate: RequestPredicate) = Unit

        override fun endNested(predicate: RequestPredicate) = Unit

        override fun route(
            predicate: RequestPredicate,
            handlerFunction: HandlerFunction<*>,
        ) {
            val before = paths.size
            predicate.accept(PredicatePaths(paths))
            check(paths.size > before) { "A route with no path predicate: $predicate" }
        }

        override fun resources(lookupFunction: Function<ServerRequest, Mono<org.springframework.core.io.Resource>>) {
            error("A resource route is outside every scope")
        }

        override fun attributes(attributes: Map<String, Any>) = Unit

        override fun unknown(routerFunction: RouterFunction<*>) {
            error("An unknown router function: $routerFunction")
        }
    }

    private class PredicatePaths(
        private val paths: MutableList<String>,
    ) : RequestPredicates.Visitor {
        override fun method(methods: Set<HttpMethod>) = Unit

        override fun path(pattern: String) {
            paths += pattern
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun pathExtension(extension: String) = Unit

        override fun header(
            name: String,
            value: String,
        ) = Unit

        override fun queryParam(
            name: String,
            value: String,
        ) = Unit

        override fun version(version: String) = Unit

        override fun startAnd() = Unit

        override fun and() = Unit

        override fun endAnd() = Unit

        override fun startOr() = Unit

        override fun or() = Unit

        override fun endOr() = Unit

        override fun startNegate() = Unit

        override fun endNegate() = Unit

        override fun unknown(predicate: RequestPredicate) = Unit
    }

    private fun functionalRoutes(): List<String> {
        val collector = PathCollector()
        context
            .getBeansOfType(RouterFunction::class.java)
            .values
            .forEach { it.accept(collector) }
        return collector.paths
    }

    private fun annotatedRoutes(): List<String> =
        context
            .getBeansOfType(RequestMappingInfoHandlerMapping::class.java)
            .values
            .flatMap { it.handlerMethods.keys }
            .flatMap { it.patternsCondition.patterns }
            .map { it.patternString }

    private fun inScope(path: String): Boolean = SCOPES.any { it.matches(pathContainer(path)) }

    private fun pathContainer(path: String) =
        org.springframework.http.server.PathContainer
            .parsePath(path.substringBefore("/{").substringBefore("/**"))

    @Test
    fun `every functional route is under v1 or admin`() {
        val routes = functionalRoutes()

        assertTrue("/v1/chat/completions" in routes) { routes.toString() }
        assertEquals(emptyList<String>(), routes.filterNot { inScope(it) })
    }

    @Test
    fun `every annotated route is under v1, under admin, or on the exact open list`() {
        val routes = annotatedRoutes()

        assertEquals(emptyList<String>(), routes.filterNot { inScope(it) || it in OPEN })
    }

    @Test
    fun `actuator exposes health and nothing else`() {
        val routes = annotatedRoutes().filter { it.startsWith("/actuator") }

        assertTrue("/actuator/health" in routes) { routes.toString() }
        assertTrue(OPEN.containsAll(routes)) { routes.toString() }
    }

    private companion object {
        val SCOPES = listOf("/v1/**", "/admin/**").map(PathPatternParser.defaultInstance::parse)
        val OPEN = setOf("/actuator/health", "/actuator/health/{*path}")
    }
}
