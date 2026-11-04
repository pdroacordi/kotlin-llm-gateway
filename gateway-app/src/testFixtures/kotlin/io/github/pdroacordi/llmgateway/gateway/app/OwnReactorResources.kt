package io.github.pdroacordi.llmgateway.gateway.app

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.http.client.ReactorResourceFactory

/** Keeps a context off the JVM's global Reactor resources, which its close would dispose under every other context. */
class OwnReactorResources : BeanPostProcessor {
    override fun postProcessBeforeInitialization(
        bean: Any,
        beanName: String,
    ): Any = bean.also { if (it is ReactorResourceFactory) it.isUseGlobalResources = false }
}

/**
 * How a test starts a context it will close
 * (docs/knowledge/gotchas/reactor-resource-factory-stop-disposes-global-http-resources.md).
 */
fun isolatedGateway(): SpringApplicationBuilder =
    SpringApplicationBuilder(GatewayApplication::class.java, OwnReactorResources::class.java)
