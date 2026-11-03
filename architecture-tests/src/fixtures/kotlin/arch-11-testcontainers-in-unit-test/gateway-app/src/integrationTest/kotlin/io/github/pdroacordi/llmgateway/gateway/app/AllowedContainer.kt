package io.github.pdroacordi.llmgateway.gateway.app

import io.github.pdroacordi.llmgateway.testsupport.SharedPostgres
import org.testcontainers.containers.GenericContainer

class AllowedContainer {
    val container: GenericContainer<*> = SharedPostgres.container
}
