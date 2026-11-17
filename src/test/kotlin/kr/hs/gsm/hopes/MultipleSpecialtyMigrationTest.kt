package kr.hs.gsm.hopes

import java.sql.DriverManager
import java.util.UUID
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MultipleSpecialtyMigrationTest {
    @Test
    fun `existing single specialty is migrated and account deletion cascades to selections`() {
        val url = "jdbc:h2:mem:multi-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        fun flyway() = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/h2")
        flyway().target("11").load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { sql ->
                sql.executeUpdate("""
                    INSERT INTO users(id,email,password_hash,username,nickname,profile_info,theme,custom_prompt,created_at)
                    VALUES(1,'migration@example.invalid','not-a-password','migration','migration','','LIGHT','',CURRENT_TIMESTAMP)
                """.trimIndent())
                sql.executeUpdate("INSERT INTO chat_preferences(user_id,department_id,specialty_id,version) VALUES(1,1,104,0)")
            }
            flyway().load().migrate()
            connection.createStatement().use { sql ->
                sql.executeQuery("SELECT specialty_id,selection_order FROM chat_preference_specialties WHERE user_id=1").use {
                    it.next()
                    assertEquals(104L, it.getLong(1))
                    assertEquals(0, it.getInt(2))
                }
                sql.executeUpdate("DELETE FROM users WHERE id=1")
                sql.executeQuery("SELECT COUNT(*) FROM chat_preference_specialties").use {
                    it.next()
                    assertEquals(0, it.getInt(1))
                }
            }
        }
    }
}
