package io.github.pdroacordi.llmgateway.testsupport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SharedPostgresTest {
    // First in this class and no other class touches SharedPostgres, so these really are the first accesses.
    @Test
    @Order(1)
    fun `two concurrent first accesses get the same started container`() {
        val threads = 2
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val ids =
                List(threads) {
                    pool.submit(
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            SharedPostgres.container.containerId
                        },
                    )
                }.map { it.get(90, TimeUnit.SECONDS) }

            assertEquals(1, ids.toSet().size, ids.toString())
            assertTrue(SharedPostgres.container.isRunning)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    @Order(2)
    fun `select 1 runs over JDBC on the mapped port`() {
        query("select 1") { assertEquals(1, it.getInt(1)) }
    }

    @Test
    @Order(3)
    fun `the server is the PostgreSQL version deploy compose yaml runs`() {
        query("show server_version") { result ->
            val version = result.getString(1)
            assertTrue(version.startsWith("18.6")) { version }
        }
    }

    private fun query(
        sql: String,
        check: (ResultSet) -> Unit,
    ) {
        val postgres = SharedPostgres.container
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    assertTrue(result.next())
                    check(result)
                }
            }
        }
    }
}
