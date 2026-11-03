package io.github.pdroacordi.llmgateway.mockprovider.container

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * Builds the image consumers use (ADR-FND-03-002) from `mock-provider/Dockerfile` and the boot jar, which the build
 * passes as the system property `mockProvider.jar`. Needs Docker: it runs in the `integrationTest` suite (ADR-016) and
 * fails, never skips, without a daemon.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MockProviderImageTest {
    private val jar: Path =
        Path.of(
            checkNotNull(System.getProperty("mockProvider.jar")) { "mockProvider.jar is not set" },
        )

    // The Dockerfile sits beside build/, at the module root; Gradle runs tests in the module directory.
    private val dockerfile: Path = Path.of("Dockerfile").toAbsolutePath()

    // deleteOnExit (the default) removes the built image when the JVM ends.
    private val container =
        MockProviderContainer(
            ImageFromDockerfile()
                .withFileFromPath("Dockerfile", dockerfile)
                .withFileFromPath("mock-provider.jar", jar),
        ).withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(OK))
            .withStartupTimeout(Duration.ofSeconds(STARTUP_SECONDS))

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build()

    @BeforeAll
    fun start() = container.start()

    @AfterAll
    fun stop() = container.stop()

    @Test
    fun `the container reports health UP on the mapped port`() {
        val response = http.send(request("/actuator/health").GET().build(), HttpResponse.BodyHandlers.ofString())

        assertEquals(OK, response.statusCode())
        assertTrue("\"status\":\"UP\"" in response.body()) { response.body() }
    }

    @Test
    fun `the container answers a chat completion`() {
        val body = """{"model":"mock-chat","messages":[{"role":"user","content":"Hello from a container"}]}"""
        val response =
            http.send(
                request("/v1/chat/completions")
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer sk-test-container")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        assertEquals(OK, response.statusCode(), response.body())
        assertTrue("\"object\":\"chat.completion\"" in response.body()) { response.body() }
        assertTrue(response.headers().firstValue("x-request-id").isPresent)
    }

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create("http://${container.host}:${container.getMappedPort(PORT)}$path"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))

    // Kotlin cannot infer GenericContainer's self type, so a subclass fixes it.
    private class MockProviderContainer(
        image: ImageFromDockerfile,
    ) : GenericContainer<MockProviderContainer>(image)

    private companion object {
        const val PORT = 8090
        const val OK = 200
        const val STARTUP_SECONDS = 90L
        const val TIMEOUT_SECONDS = 10L
    }
}
