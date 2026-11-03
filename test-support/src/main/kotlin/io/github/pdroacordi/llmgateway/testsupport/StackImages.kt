package io.github.pdroacordi.llmgateway.testsupport

/**
 * Images shared by `deploy/compose.yaml` and the `Shared*` fixtures. The compose file is the truth; a tag changes
 * in both places together, and `StackContractTest` fails until they agree (ADR-FND-02-008).
 */
object StackImages {
    /** PostgreSQL 18.6 on Debian trixie. */
    const val POSTGRES: String = "postgres:18.6-trixie"

    /** Redis 8 Open Source on Debian trixie. */
    const val REDIS: String = "redis:8.10.3-trixie"

    /** Redpanda, the Kafka-compatible broker, provisional until ADR-010 is settled. */
    const val REDPANDA: String = "docker.redpanda.com/redpandadata/redpanda:v26.2.6"
}
