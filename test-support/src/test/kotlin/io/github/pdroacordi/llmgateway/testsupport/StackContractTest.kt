package io.github.pdroacordi.llmgateway.testsupport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Keeps `deploy/compose.yaml` and [StackImages] equal and the compose file's ports and names in the shape the
 * worktree overrides rely on (ADR-FND-02-002, ADR-FND-02-008). Docker-free.
 */
class StackContractTest {
    @Test
    fun `deploy compose yaml keeps the stack contract`() {
        val violations = stackContractViolations(File(COMPOSE_FILE).readText())
        assertTrue(violations.isEmpty()) { violations.joinToString(separator = "\n", prefix = "\n") }
    }

    @Test
    fun `the services the fixtures mirror are in the compose file`() {
        val services = services(File(COMPOSE_FILE).readText())
        assertTrue(services.keys.containsAll(MIRRORED_IMAGES.keys)) { "services: ${services.keys}" }
    }

    @Test
    fun `an image that differs from StackImages is named with both values`() {
        val drifted = compose(Overrides(redisImage = "redis:8.10.4-trixie"))
        assertFlags(
            stackContractViolations(drifted),
            "redis",
            "redis:8.10.4-trixie",
            StackImages.REDIS,
        )
    }

    @Test
    fun `an image without a tag or tagged latest is rejected`() {
        val untagged = compose(Overrides(grafanaImage = "grafana/grafana"))
        assertFlags(stackContractViolations(untagged), "grafana", "grafana/grafana", "explicit tag")
        val latest = compose(Overrides(grafanaImage = "grafana/grafana:latest"))
        assertFlags(stackContractViolations(latest), "grafana", "grafana/grafana:latest", "explicit tag")
    }

    @Test
    fun `a registry port is not mistaken for a tag`() {
        val withRegistryPort = compose(Overrides(grafanaImage = "registry.local:5000/grafana/grafana"))
        assertFlags(stackContractViolations(withRegistryPort), "grafana", "registry.local:5000/grafana/grafana", "tag")
    }

    @Test
    fun `a container_name is rejected`() {
        val named = compose(Overrides(extraRedis = "    container_name: llmgw-redis\n"))
        assertFlags(stackContractViolations(named), "redis", "container_name", "llmgw-redis")
    }

    @Test
    fun `a port bound to all interfaces is rejected`() {
        val open = compose(Overrides(redisPort = "0.0.0.0:\${LLMGW_REDIS_PORT:-6390}:6379"))
        assertFlags(stackContractViolations(open), "redis", "0.0.0.0:\${LLMGW_REDIS_PORT:-6390}:6379", "127.0.0.1")
    }

    @Test
    fun `a port without its LLMGW variable is rejected`() {
        val fixed = compose(Overrides(redisPort = "127.0.0.1:6390:6379"))
        assertFlags(stackContractViolations(fixed), "redis", "127.0.0.1:6390:6379", "LLMGW_REDIS_PORT")
    }

    @Test
    fun `a default port that differs from the CLAUDE md table is rejected`() {
        val moved = compose(Overrides(redisPort = "127.0.0.1:\${LLMGW_REDIS_PORT:-6379}:6379"))
        assertFlags(stackContractViolations(moved), "redis", "6379", "6390")
    }

    @Test
    fun `a redpanda that advertises another external address is rejected`() {
        val advertised = compose(Overrides(redpandaAdvertised = "external://localhost:\${LLMGW_KAFKA_PORT:-19092}"))
        assertFlags(
            stackContractViolations(advertised),
            "redpanda",
            "external://127.0.0.1:\${LLMGW_KAFKA_PORT:-19092}",
            "advertise",
        )
    }

    @Test
    fun `a container port that differs from the table is rejected`() {
        val remapped = compose(Overrides(redisPort = "127.0.0.1:\${LLMGW_REDIS_PORT:-6390}:6380"))
        assertFlags(stackContractViolations(remapped), "redis", "6380", "6379")
    }

    @Test
    fun `a network_mode is rejected`() {
        val hostNetwork = compose(Overrides(extraRedis = "    network_mode: host\n"))
        assertFlags(stackContractViolations(hostNetwork), "redis", "network_mode", "host")
    }

    @Test
    fun `a redpanda external listener on another port than the published one is rejected`() {
        val listener = compose(Overrides(redpandaKafkaAddr = "internal://0.0.0.0:9092,external://0.0.0.0:19093"))
        assertFlags(stackContractViolations(listener), "redpanda", "external listener port 19093", "19092")
    }

    @Test
    fun `the minimal valid compose file used by these tests has no violations`() {
        assertEquals(emptyList<String>(), stackContractViolations(compose()))
    }

    private fun assertFlags(
        violations: List<String>,
        vararg fragments: String,
    ) {
        assertTrue(violations.any { line -> fragments.all { it in line } }) {
            "expected a violation naming ${fragments.toList()}, got: $violations"
        }
    }

    // One value per drift case; every default is valid.
    private data class Overrides(
        val redisImage: String = StackImages.REDIS,
        val grafanaImage: String = "grafana/grafana:13.3.0",
        val redisPort: String = "127.0.0.1:\${LLMGW_REDIS_PORT:-6390}:6379",
        val redpandaAdvertised: String = "external://127.0.0.1:\${LLMGW_KAFKA_PORT:-19092}",
        val redpandaKafkaAddr: String = "internal://0.0.0.0:9092,external://0.0.0.0:19092",
        val extraRedis: String = "",
    )

    // One service per default port, enough for every rule; each test changes one value.
    private fun compose(overrides: Overrides = Overrides()): String =
        """
        |name: llmgw
        |services:
        |  postgres:
        |    image: ${StackImages.POSTGRES}
        |    ports: ["127.0.0.1:${'$'}{LLMGW_POSTGRES_PORT:-5440}:5432"]
        |  redis:
        |    image: ${overrides.redisImage}
        |    ports: ["${overrides.redisPort}"]
        |${overrides.extraRedis}  redpanda:
        |    image: ${StackImages.REDPANDA}
        |    command:
        |      - redpanda
        |      - start
        |      - --kafka-addr
        |      - "${overrides.redpandaKafkaAddr}"
        |      - --advertise-kafka-addr
        |      - "internal://redpanda:9092,${overrides.redpandaAdvertised}"
        |    ports: ["127.0.0.1:${'$'}{LLMGW_KAFKA_PORT:-19092}:19092"]
        |  otel-collector:
        |    image: otel/opentelemetry-collector:0.164.0
        |    ports:
        |      - "127.0.0.1:${'$'}{LLMGW_OTLP_GRPC_PORT:-4317}:4317"
        |      - "127.0.0.1:${'$'}{LLMGW_OTLP_HTTP_PORT:-4318}:4318"
        |  jaeger:
        |    image: jaegertracing/jaeger:2.22.0
        |    ports: ["127.0.0.1:${'$'}{LLMGW_JAEGER_UI_PORT:-16686}:16686"]
        |  prometheus:
        |    image: prom/prometheus:v3.15.1-busybox
        |    ports: ["127.0.0.1:${'$'}{LLMGW_PROMETHEUS_PORT:-9090}:9090"]
        |  grafana:
        |    image: ${overrides.grafanaImage}
        |    ports: ["127.0.0.1:${'$'}{LLMGW_GRAFANA_PORT:-3301}:3000"]
        """.trimMargin()

    private companion object {
        // Gradle runs tests in the module directory; build.gradle.kts declares the file as a test input.
        const val COMPOSE_FILE = "../deploy/compose.yaml"
    }
}

/** Compose services whose image [StackImages] mirrors. */
private val MIRRORED_IMAGES: Map<String, String> =
    mapOf(
        "postgres" to StackImages.POSTGRES,
        "redis" to StackImages.REDIS,
        "redpanda" to StackImages.REDPANDA,
    )

/**
 * Published ports per service as `<variable>:<host default>:<container port>`; the host defaults equal the
 * CLAUDE.md "Local ports" table.
 */
private val DEFAULT_PORTS: Map<String, List<String>> =
    mapOf(
        "postgres" to listOf("LLMGW_POSTGRES_PORT:5440:5432"),
        "redis" to listOf("LLMGW_REDIS_PORT:6390:6379"),
        "redpanda" to listOf("LLMGW_KAFKA_PORT:19092:19092"),
        "otel-collector" to listOf("LLMGW_OTLP_GRPC_PORT:4317:4317", "LLMGW_OTLP_HTTP_PORT:4318:4318"),
        "jaeger" to listOf("LLMGW_JAEGER_UI_PORT:16686:16686"),
        "prometheus" to listOf("LLMGW_PROMETHEUS_PORT:9090:9090"),
        "grafana" to listOf("LLMGW_GRAFANA_PORT:3301:3000"),
    )

private val EXTERNAL_LISTENER = Regex("""external://[^,]*:(\d+)""")

private const val REDPANDA_ADVERTISED = "external://127.0.0.1:\${LLMGW_KAFKA_PORT:-19092}"

// 127.0.0.1:${LLMGW_<X>_PORT:-<n>}:<m>
private val PORT_ENTRY = Regex("""^127\.0\.0\.1:\$\{(LLMGW_[A-Z_]+_PORT):-(\d+)\}:(\d+)$""")

/** Every way [composeText] breaks the stack contract, one line each, naming the service and both values. */
fun stackContractViolations(composeText: String): List<String> {
    val services = services(composeText)
    return MIRRORED_IMAGES.mapNotNull { (service, expected) ->
        val actual = services[service]?.get("image")
        if (actual == expected) null else "$service: compose image $actual, StackImages has $expected"
    } +
        services.flatMap { (name, service) -> serviceViolations(name, service) } +
        (DEFAULT_PORTS.keys - services.keys).map { "$it: missing from the compose file" } +
        redpandaViolations(services["redpanda"])
}

private fun serviceViolations(
    name: String,
    service: Map<String, Any?>,
): List<String> {
    val violations = mutableListOf<String>()
    val image = service["image"]?.toString()
    if (image == null || !hasExplicitTag(image)) {
        violations += "$name: image $image needs an explicit tag that is not latest"
    }
    service["container_name"]?.let { violations += "$name: container_name $it is set; the project name prefixes names" }
    service["network_mode"]?.let { violations += "$name: network_mode $it is set; publish ports on 127.0.0.1" }
    val ports = (service["ports"] as? List<*>).orEmpty().map { it.toString() }
    val expected = DEFAULT_PORTS[name].orEmpty()
    val declared =
        ports.mapNotNull { entry ->
            val match = PORT_ENTRY.matchEntire(entry)
            if (match == null) {
                violations += "$name: port $entry must be 127.0.0.1:\${LLMGW_<X>_PORT:-<default>}:<port> " +
                    "(expected one of ${expected.map(::portEntry)})"
                null
            } else {
                "${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]}"
            }
        }
    (declared - expected.toSet()).forEach { violations += "$name: port mapping $it, CLAUDE.md table has $expected" }
    (expected - declared.toSet()).forEach { violations += "$name: missing port mapping $it, compose has $declared" }
    return violations
}

// LLMGW_X_PORT:5440:5432 -> 127.0.0.1:${LLMGW_X_PORT:-5440}:5432
private fun portEntry(mapping: String): String {
    val (variable, host, container) = mapping.split(':')
    return "127.0.0.1:\${$variable:-$host}:$container"
}

private fun hasExplicitTag(image: String): Boolean {
    val lastSegment = image.substringAfterLast('/')
    val tag = lastSegment.substringAfter(':', missingDelimiterValue = "")
    return tag.isNotEmpty() && tag != "latest"
}

private fun redpandaViolations(redpanda: Map<String, Any?>?): List<String> {
    val arguments = (redpanda?.get("command") as? List<*>).orEmpty().map { it.toString() }
    val command = arguments.joinToString(" ")
    val violations = mutableListOf<String>()
    if (REDPANDA_ADVERTISED !in command) {
        violations += "redpanda: command must advertise $REDPANDA_ADVERTISED, has: $command"
    }
    // The external listener must be the container port that is published, or the host port reaches nothing.
    val flag = arguments.indexOf("--kafka-addr")
    val kafkaAddr = if (flag < 0) null else arguments.getOrNull(flag + 1)
    val listenerPort = kafkaAddr?.let { EXTERNAL_LISTENER.find(it)?.groupValues?.get(1) }
    val publishedPort = DEFAULT_PORTS.getValue("redpanda").single().substringAfterLast(':')
    if (listenerPort != publishedPort) {
        violations += "redpanda: external listener port $listenerPort in --kafka-addr $kafkaAddr, " +
            "published container port $publishedPort"
    }
    return violations
}

// SnakeYAML returns untyped maps; a compose file's services section is a map of maps.
@Suppress("UNCHECKED_CAST")
private fun services(composeText: String): Map<String, Map<String, Any?>> {
    val root = Yaml().load<Map<String, Any?>>(composeText)
    return (root["services"] as? Map<String, Map<String, Any?>>).orEmpty()
}
