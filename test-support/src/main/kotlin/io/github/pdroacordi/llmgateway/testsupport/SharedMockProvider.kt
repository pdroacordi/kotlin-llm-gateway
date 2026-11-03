package io.github.pdroacordi.llmgateway.testsupport

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
 * One mock-provider container per test JVM, built from the boot jar and `mock-provider/Dockerfile` that
 * `llmgw.integration-test` passes when the module declares `mockProvider(project(":mock-provider"))`
 * (ADR-FND-02-010). Started on first access, removed by Ryuk when the JVM ends; never stop it. State is shared:
 * call [reset] in `@BeforeEach`.
 */
object SharedMockProvider {
    private const val PORT = 8090
    private const val OK = 200
    private const val NO_SUCCESS = 300
    private const val START_TIMEOUT_SECONDS = 90L
    private const val RESET_TIMEOUT_SECONDS = 10L
    private const val MISSING_WIRING = "declare mockProvider(project(\":mock-provider\")) in this module's build"

    /** The started container; the first access builds the image and starts it. */
    val container: GenericContainer<*> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val jar =
            checkNotNull(System.getProperty("mockProvider.jar")) { "mockProvider.jar is not set: $MISSING_WIRING" }
        val dockerfile =
            checkNotNull(System.getProperty("mockProvider.dockerfile")) {
                "mockProvider.dockerfile is not set: $MISSING_WIRING"
            }
        // deleteOnExit (the default) removes the built image when the JVM ends.
        MockProviderContainer(
            ImageFromDockerfile()
                .withFileFromPath("Dockerfile", Path.of(dockerfile))
                .withFileFromPath("mock-provider.jar", Path.of(jar)),
        ).withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(OK))
            .withStartupTimeout(Duration.ofSeconds(START_TIMEOUT_SECONDS))
            .also { it.start() }
    }

    /** `http://<host>:<mapped port>`, without a trailing slash. */
    val baseUrl: String
        get() = "http://${container.host}:${container.getMappedPort(PORT)}"

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(RESET_TIMEOUT_SECONDS)).build()
    }

    /** Removes scenarios, counts and the journal (`POST /__mock/reset`); fails on a non-2xx status. */
    fun reset() {
        val request =
            HttpRequest
                .newBuilder(URI.create("$baseUrl/__mock/reset"))
                .timeout(Duration.ofSeconds(RESET_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in OK until NO_SUCCESS) {
            "POST /__mock/reset returned ${response.statusCode()}: ${response.body()}"
        }
    }

    // Kotlin cannot infer GenericContainer's self type, so a subclass fixes it.
    private class MockProviderContainer(
        image: ImageFromDockerfile,
    ) : GenericContainer<MockProviderContainer>(image)
}
