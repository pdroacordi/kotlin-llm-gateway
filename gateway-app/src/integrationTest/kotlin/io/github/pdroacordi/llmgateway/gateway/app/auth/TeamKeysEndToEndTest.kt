package io.github.pdroacordi.llmgateway.gateway.app.auth

import io.github.pdroacordi.llmgateway.gateway.core.team.KeyHasher
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamStorePort
import io.github.pdroacordi.llmgateway.gateway.infra.cache.CachingTeamStore
import io.github.pdroacordi.llmgateway.testsupport.SharedPostgres
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager
import java.util.HexFormat
import javax.sql.DataSource

class TeamKeysEndToEndTest(
    @Autowired private val context: ApplicationContext,
    @Autowired private val hasher: KeyHasher,
    @Autowired private val store: TeamStorePort,
) : PostgresBackedTest() {
    @Test
    fun `the real store is the one in use, and JDBC left no DataSource behind`() {
        assertTrue(store is CachingTeamStore) { store::class.toString() }
        assertTrue(context.getBeanNamesForType(DataSource::class.java).isEmpty())
    }

    @Test
    fun `admin create, chat, revoke, 401`() {
        val key = createKey(createTeam())
        val plain = key.get("key").stringValue()

        chatAs(plain).expectStatus().isOk
        revoke(key)

        chatAs(plain).expectStatus().isUnauthorized
    }

    @Test
    fun `the table holds a 32-byte digest and a hint, never the plain value`() {
        val key = createKey(createTeam())
        val plain = key.get("key").stringValue()

        DriverManager.getConnection(DATABASE.jdbcUrl, DATABASE.username, DATABASE.password).use { connection ->
            connection.prepareStatement("SELECT * FROM team_keys WHERE id = ?::uuid").use { statement ->
                statement.setString(1, key.get("id").stringValue())
                statement.executeQuery().use { row ->
                    assertTrue(row.next())
                    val digest = row.getBytes("digest")
                    assertEquals(32, digest.size)
                    assertEquals(hasher.digest(plain).hex, HexFormat.of().formatHex(digest))
                    assertEquals(plain.take(10), row.getString("hint"))
                    assertEquals(1, row.getShort("secret_version").toInt())
                    (1..row.metaData.columnCount).forEach { column ->
                        assertFalse(plain in row.getString(column).orEmpty()) { row.metaData.getColumnName(column) }
                    }
                }
            }
        }
    }

    private companion object {
        val DATABASE = SharedPostgres.database("team_keys_end_to_end")

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            register(registry, DATABASE)
        }
    }
}
