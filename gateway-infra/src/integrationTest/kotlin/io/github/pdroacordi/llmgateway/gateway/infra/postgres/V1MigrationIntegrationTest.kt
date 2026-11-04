package io.github.pdroacordi.llmgateway.gateway.infra.postgres

import io.github.pdroacordi.llmgateway.testsupport.SharedPostgres
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException

class V1MigrationIntegrationTest {
    private fun strings(sql: String): Set<String> =
        TestPostgres.jdbc(DATABASE) { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildSet {
                        while (rows.next()) add(rows.getString(1))
                    }
                }
            }
        }

    @Test
    fun `the tables exist`() {
        val tables = strings("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")

        assertTrue(tables.containsAll(setOf("teams", "team_keys"))) { tables.toString() }
    }

    @Test
    fun `the four indexes of the spec exist`() {
        val indexes = strings("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")

        val expected = setOf("team_keys_digest_key", "team_keys_team_name_active", "team_keys_team_id_id", "teams_pkey")
        assertTrue(indexes.containsAll(expected)) { indexes.toString() }
    }

    @Test
    fun `a digest that is not 32 bytes is refused`() {
        val failure =
            assertThrows<SQLException> {
                TestPostgres.execute(
                    DATABASE,
                    """
                    WITH t AS (
                        INSERT INTO teams VALUES ('01900000-0000-7000-8000-00000000000a', 'short-digest', now())
                        RETURNING id
                    )
                    INSERT INTO team_keys (id, team_id, name, digest, secret_version, hint, created_at)
                    SELECT '01900000-0000-7000-8000-00000000000b', id, 'k', '\x00', 1, 'h', now() FROM t
                    """.trimIndent(),
                )
            }

        assertEquals("23514", failure.sqlState)
        assertTrue("team_keys_digest_length" in failure.message.orEmpty()) { failure.message.orEmpty() }
    }

    @Test
    fun `a second run applies nothing`() {
        assertEquals(0, TestPostgres.migrate(DATABASE).migrationsExecuted)
    }

    @Test
    fun `there is exactly one migration, V1`() {
        assertEquals(setOf("1"), strings("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL"))
    }

    private companion object {
        val DATABASE = SharedPostgres.database("v1_migration").also { TestPostgres.migrate(it) }
    }
}
