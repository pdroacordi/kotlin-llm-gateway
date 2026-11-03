package io.github.pdroacordi.llmgateway.mockprovider

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class MockProviderApplication

fun main(vararg args: String) {
    runApplication<MockProviderApplication>(*args)
}
