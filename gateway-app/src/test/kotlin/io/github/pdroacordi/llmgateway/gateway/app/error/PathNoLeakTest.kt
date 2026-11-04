package io.github.pdroacordi.llmgateway.gateway.app.error

import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggerGroups
import org.springframework.boot.logging.LoggingSystem
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A key pasted into a URL stays out of the error body, and so out of what the Jackson encoder logs at DEBUG. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
class PathNoLeakTest(
    @LocalServerPort private val port: Int,
    @Autowired private val logging: LoggingSystem,
    @Autowired private val groups: LoggerGroups,
) {
    // Not WebTestClient: its ExchangeFunctions logs the URL at DEBUG, in this same output.
    private val client = HttpClient.newHttpClient()

    private var mark = 0

    // Skips the class-level capture: docs/knowledge/gotchas/output-capture-all-includes-the-class-level-capture.md
    @BeforeEach
    fun markLog(output: CapturedOutput) {
        mark = output.length
    }

    // Set here, not by property: a level from a property outlives this class in the shared JVM, and the client of
    // the next test then logs its own request body into that test's capture.
    @BeforeEach
    fun debugOn() = setLevels(LogLevel.DEBUG)

    @AfterEach
    fun debugOff() = setLevels(null)

    private fun setLevels(level: LogLevel?) {
        logging.setLogLevel(GATEWAY_LOGGER, level)
        checkNotNull(groups.get("web")).configureLogLevel(level, logging::setLogLevel)
    }

    private fun ownLog(output: CapturedOutput): String = output.all.substring(mark)

    @ParameterizedTest(name = "{0} {1} gives {3}")
    @MethodSource("requests")
    fun `a key-shaped path segment reaches neither the body nor the DEBUG output`(
        method: String,
        uri: String,
        authorization: String?,
        status: Int,
        output: CapturedOutput,
    ) {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$uri"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .apply { authorization?.let { header("Authorization", it) } }
                .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body()

        assertEquals(status, response.statusCode()) { body }
        assertFalse(TOKEN in body) { body }
        // The mapper logs after the write. Both lines prove DEBUG was on, so the absence below means something.
        await().atMost(Duration.ofSeconds(5)).until { "Request rejected with status $status" in ownLog(output) }
        assertTrue("Encoding [OpenAiErrorEnvelope" in ownLog(output)) { ownLog(output) }
        assertFalse(TOKEN in ownLog(output)) { ownLog(output) }
    }

    private companion object {
        const val GATEWAY_LOGGER = "io.github.pdroacordi.llmgateway"

        // The shape of a key, and not a key.
        val TOKEN = "llmgw_" + "pathCanary-_".repeat(4).take(43)

        @JvmStatic
        fun requests(): List<Arguments> =
            listOf(
                Arguments.of("GET", "/actuator;k=$TOKEN", null, 404),
                Arguments.of("GET", "/nope/$TOKEN", null, 404),
                Arguments.of("POST", "/nope/$TOKEN", null, 404),
                Arguments.of("GET", "/nope?access_token=$TOKEN", null, 404),
                Arguments.of("GET", "/nope;key=$TOKEN", null, 404),
                Arguments.of("GET", "/v1/$TOKEN", null, 401),
                Arguments.of("GET", "/v1/$TOKEN", TestKeys.BEARER, 404),
                Arguments.of("GET", "/v1/chat/completions/$TOKEN", TestKeys.BEARER, 404),
                Arguments.of("GET", "/v1/chat/completions;key=$TOKEN", TestKeys.BEARER, 405),
                Arguments.of("GET", "/v1/chat/completions?key=$TOKEN", TestKeys.BEARER, 405),
                Arguments.of("GET", "/admin/v1/$TOKEN", TestKeys.ADMIN_BEARER, 404),
                Arguments.of("DELETE", "/admin/v1/teams;key=$TOKEN", TestKeys.ADMIN_BEARER, 405),
            )
    }
}
