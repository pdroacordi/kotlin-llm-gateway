package io.github.pdroacordi.llmgateway.testsupport

import org.testcontainers.utility.DockerImageName

class ContainerInOwnUnitTest {
    val image: DockerImageName = DockerImageName.parse(StackImages.REDIS)
}
