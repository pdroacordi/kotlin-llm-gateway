package io.github.pdroacordi.llmgateway.testsupport

import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration

/** Where one database of [SharedPostgres] is; the password is the fixture's fake one. */
data class PostgresDatabase(
    val host: String,
    val port: Int,
    val name: String,
    val username: String,
    val password: String,
) {
    val jdbcUrl: String get() = "jdbc:postgresql://$host:$port/$name"
    val r2dbcUrl: String get() = "r2dbc:postgresql://$host:$port/$name"
}

/**
 * One Postgres container per test JVM, started on first access and removed by Ryuk when the JVM ends; never stop it
 * (ADR-FND-02-007). Database, user and password are `llmgw`. State is shared: create a schema or a database per test
 * class.
 */
object SharedPostgres {
    private const val START_TIMEOUT_SECONDS = 60L
    private const val NAME = "llmgw"
    private const val DUPLICATE_DATABASE = "42P04"
    private val DATABASE_NAME = Regex("[a-z][a-z0-9_]{0,62}")

    /** The started container; the first access starts it, concurrent first accesses wait for the same one. */
    val container: PostgreSQLContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PostgreSQLContainer(StackImages.POSTGRES)
            .withDatabaseName(NAME)
            .withUsername(NAME)
            .withPassword(NAME)
            .withStartupTimeout(Duration.ofSeconds(START_TIMEOUT_SECONDS))
            .also { it.start() }
    }

    /** A database of its own for one test class, created once; a repeated call changes nothing (ADR-GW-04-010). */
    fun database(name: String): PostgresDatabase {
        require(DATABASE_NAME.matches(name)) { "Not a plain database name: $name" }
        val postgres = container
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                try {
                    statement.execute("CREATE DATABASE $name")
                } catch (failure: SQLException) {
                    if (failure.sqlState != DUPLICATE_DATABASE) throw failure
                }
            }
        }
        return PostgresDatabase(postgres.host, postgres.firstMappedPort, name, postgres.username, postgres.password)
    }
}
