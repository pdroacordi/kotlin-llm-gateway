package io.github.pdroacordi.llmgateway.gateway.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GatewayApplication

fun main(vararg args: String) {
    runApplication<GatewayApplication>(*args)
}
