package io.github.pdroacordi.llmgateway.gateway.core

import java.net.URI

// java.sql.Connection is mentioned in a comment only.
class AllowedUri {
    val base: URI = java.net.URI.create("https://example.test/reactor.core")
    val label = "java.net.Socket"
}
