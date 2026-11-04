package io.github.pdroacordi.llmgateway.gateway.infra.postgres

import io.github.pdroacordi.llmgateway.testsupport.PostgresDatabase
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.ValidationDepth
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration

/** What the Postgres suites share: the production migration, application.yaml's pool, and JDBC to look at rows. */
internal object TestPostgres {
    fun migrate(database: PostgresDatabase): MigrateResult =
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration/gateway")
            .load()
            .migrate()

    /** Warmed, as the app's warm-up leaves it, unless [warm] is false: 500 ms is too short for a cold connect. */
    fun pool(
        database: PostgresDatabase,
        maxSize: Int = 10,
        maxAcquireTime: Duration = Duration.ofMillis(500),
        warm: Boolean = true,
    ): ConnectionPool {
        val options =
            ConnectionFactoryOptions
                .parse(database.r2dbcUrl)
                .mutate()
                .option(ConnectionFactoryOptions.USER, database.username)
                .option(ConnectionFactoryOptions.PASSWORD, database.password)
                .build()
        val idle = minOf(IDLE_CONNECTIONS, maxSize)
        val pool =
            ConnectionPool(
                ConnectionPoolConfiguration
                    .builder(ConnectionFactories.get(options))
                    .initialSize(idle)
                    .minIdle(idle)
                    .maxSize(maxSize)
                    .maxAcquireTime(maxAcquireTime)
                    .maxCreateConnectionTime(Duration.ofSeconds(2))
                    .maxValidationTime(Duration.ofMillis(250))
                    .validationDepth(ValidationDepth.REMOTE)
                    .maxIdleTime(Duration.ofMinutes(10))
                    .maxLifeTime(Duration.ofMinutes(30))
                    .build(),
            )
        if (warm) pool.warmup().block(Duration.ofSeconds(20))
        return pool
    }

    private const val IDLE_CONNECTIONS = 2

    fun <T> jdbc(
        database: PostgresDatabase,
        block: (Connection) -> T,
    ): T = DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use(block)

    fun execute(
        database: PostgresDatabase,
        sql: String,
    ) {
        jdbc(database) { connection -> connection.createStatement().use { it.execute(sql) } }
    }
}
