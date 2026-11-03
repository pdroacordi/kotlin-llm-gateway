package io.github.pdroacordi.llmgateway.gateway.app

import org.testcontainers.containers.GenericContainer

class ContainerInUnitTest {
    val image = org.testcontainers.utility.DockerImageName.parse("redis:8.10.3-trixie")
    val container: GenericContainer<*>? = null
}
