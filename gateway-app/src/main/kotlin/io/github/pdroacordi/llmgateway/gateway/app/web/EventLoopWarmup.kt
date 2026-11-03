package io.github.pdroacordi.llmgateway.gateway.app.web

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.KotlinDetector
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import java.lang.reflect.Method
import kotlin.reflect.jvm.kotlinFunction

/**
 * Does at startup what the first requests would otherwise do on an event loop: ReactiveFlowKt reads
 * META-INF/services from the jars, and kotlin-reflect reads its built-ins and takes locks for each Kotlin handler
 * (docs/knowledge/gotchas/kotlinx-reactive-await-loads-context-injectors-on-first-use.md,
 * docs/knowledge/gotchas/kotlin-reflect-first-use-in-annotated-handlers-blocks-the-event-loop.md).
 */
class EventLoopWarmup(
    private val mappings: List<RequestMappingHandlerMapping>,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        Class.forName(REACTIVE_FLOW)
        mappings
            .flatMap { it.handlerMethods.values }
            .filter { KotlinDetector.isKotlinType(it.beanType) }
            .forEach { resolve(it.method) }
    }

    // What Spring's InvocableHandlerMethod asks kotlin-reflect for on every call of a Kotlin handler.
    private fun resolve(method: Method) {
        method.kotlinFunction?.parameters?.forEach { it.type.classifier }
    }

    private companion object {
        const val REACTIVE_FLOW = "kotlinx.coroutines.reactive.ReactiveFlowKt"
    }
}
