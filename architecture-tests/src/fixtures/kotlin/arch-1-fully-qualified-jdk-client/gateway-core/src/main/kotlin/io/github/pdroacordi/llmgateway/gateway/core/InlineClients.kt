package io.github.pdroacordi.llmgateway.gateway.core

class InlineClients {
    val http = java.net.http.HttpClient.newHttpClient()
    val socket = java.net.Socket("localhost", 80)
    val mono: reactor.core.publisher.Mono<String>? = null
}
