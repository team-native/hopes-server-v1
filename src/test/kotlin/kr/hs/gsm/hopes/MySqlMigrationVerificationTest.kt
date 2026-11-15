package kr.hs.gsm.hopes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.Properties
import java.util.UUID
import javax.imageio.ImageIO
import kr.hs.gsm.hopes.domain.User
import kr.hs.gsm.hopes.domain.UserRepository
import kr.hs.gsm.hopes.domain.UserRole
import kr.hs.gsm.hopes.feature.AttachmentRepository
import kr.hs.gsm.hopes.feature.AttachmentService
import kr.hs.gsm.hopes.security.AccessTokenService
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 실제 MySQL에서 마이그레이션과 연관 데이터를 검증한다. 운영 DB에는 절대 연결하지 않는다.
 *
 * 실행: 프로젝트 루트 .env 에 DATABASE_USERNAME / DATABASE_PASSWORD 를 넣고
 *   MYSQL_VERIFY=true mvn -Dtest=MySqlMigrationVerificationTest test
 * 접속 주소는 MYSQL_VERIFY_HOST (기본 localhost:3306).
 *
 * 이 테스트는 hopes_migtest_ 로 시작하는 DB만 지우고 다시 만든다.
 *  - hopes_migtest_fresh : 빈 DB에 V1~최신 전체 적용
 *  - hopes_migtest_app   : V4까지 적용하고 기존 데이터를 넣은 뒤, 서버를 띄워 V5~최신을 적용(실제 배포와 같은 흐름)
 */
@EnabledIfEnvironmentVariable(named = "MYSQL_VERIFY", matches = "true")
@SpringBootTest(
    properties =
        [
            "hopes.ai.enabled=true",
            "hopes.ai.chunks-path=classpath:data/test_rag_chunks.jsonl",
            "hopes.ai.cache-path=./target/mysql-verify/embedding.json",
            "hopes.attachments.directory=./target/mysql-verify/files",
            "hopes.attachments.cleanup-ms=86400000",
        ]
)
@AutoConfigureMockMvc
@Import(HopesAiIntegrationTest.FakeAiConfig::class)
class MySqlMigrationVerificationTest
@Autowired
constructor(
    private val mvc: MockMvc,
    private val mapper: ObjectMapper,
    private val users: UserRepository,
    private val tokens: AccessTokenService,
    private val attachments: AttachmentRepository,
    private val attachmentService: AttachmentService,
    @Value("\${hopes.attachments.directory}") private val attachmentDir: String,
) {
    companion object {
        const val PREFIX = "hopes_migtest_"
        const val APP_DB = "${PREFIX}app"
        const val LEGACY_PASSWORD = "password1"
        private val env: Properties by lazy { loadEnv() }
        val host: String by lazy { System.getenv("MYSQL_VERIFY_HOST") ?: "localhost:3306" }

        var legacyChatId = 0L

        private fun loadEnv(): Properties {
            val p = Properties()
            val file = Path.of(".env")
            if (Files.exists(file)) Files.newBufferedReader(file).use { p.load(it) }
            System.getenv().forEach { (k, v) -> if (k.startsWith("DATABASE_")) p[k] = v }
            return p
        }

        fun user() = env.getProperty("DATABASE_USERNAME") ?: error(".env 에 DATABASE_USERNAME 이 없습니다")

        fun password() = env.getProperty("DATABASE_PASSWORD") ?: ""

        fun url(db: String = "") =
            "jdbc:mysql://$host/$db?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Seoul&allowPublicKeyRetrieval=true&useSSL=false"

        fun connect(db: String = ""): Connection = DriverManager.getConnection(url(db), user(), password())

        /** 서버 기본 문자셋 그대로 만든다(운영의 createDatabaseIfNotExist 와 같은 조건). */
        fun recreate(db: String) {
            require(db.startsWith(PREFIX)) { "검증용 DB만 다시 만들 수 있습니다: $db" }
            connect().use {
                it.createStatement().execute("DROP DATABASE IF EXISTS `$db`")
                it.createStatement().execute("CREATE DATABASE `$db`")
            }
        }

        fun flyway(db: String, target: String? = null): Flyway =
            Flyway.configure()
                .dataSource(url(db), user(), password())
                .locations("classpath:db/migration/mysql")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .apply { if (target != null) target(target) }
                .load()

        /** V4 시점(1.5 이전 운영)과 같은 상태를 만든다. */
        fun seedLegacy(db: String) {
            recreate(db)
            flyway(db, "4").migrate()
            val hash = BCryptPasswordEncoder().encode(LEGACY_PASSWORD)
            val now = Timestamp.from(Instant.now())
            connect(db).use { c ->
                c.prepareStatement(
                        "INSERT INTO users(email,password_hash,username,nickname,gender,major,cohort,profile_info,theme,custom_prompt,created_at,token_version) VALUES (?,?,?,?,NULL,NULL,?,?,?,?,?,0)"
                    )
                    .apply {
                        for ((i, name) in listOf("legacy-a", "legacy-b").withIndex()) {
                            setString(1, "$name@gsm.hs.kr")
                            setString(2, hash)
                            setString(3, name)
                            setString(4, "기존사용자$i")
                            setInt(5, 9 + i)
                            setString(6, "")
                            setString(7, "LIGHT")
                            setString(8, "")
                            setTimestamp(9, now)
                            addBatch()
                        }
                        executeBatch()
                    }
                val userId =
                    c.createStatement()
                        .executeQuery("SELECT id FROM users WHERE username='legacy-a'")
                        .apply { next() }
                        .getLong(1)
                c.prepareStatement(
                        "INSERT INTO conversations(user_id,title,created_at,updated_at) VALUES (?,?,?,?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS,
                    )
                    .apply {
                        setLong(1, userId)
                        setString(2, "기존 대화")
                        setTimestamp(3, now)
                        setTimestamp(4, now)
                        executeUpdate()
                        legacyChatId = generatedKeys.apply { next() }.getLong(1)
                    }
                c.prepareStatement(
                        "INSERT INTO messages(conversation_id,role,content,client_platform,created_at) VALUES (?,?,?,?,?)"
                    )
                    .apply {
                        for ((role, text) in listOf("USER" to "기존 질문", "ASSISTANT" to "기존 답변")) {
                            setLong(1, legacyChatId)
                            setString(2, role)
                            setString(3, text)
                            setString(4, "APP")
                            setTimestamp(5, now)
                            addBatch()
                        }
                        executeBatch()
                    }
                c.prepareStatement("INSERT INTO inquiries(user_id,content,created_at) VALUES (?,?,?)").apply {
                    setLong(1, userId)
                    setString(2, "기존 문의")
                    setTimestamp(3, now)
                    executeUpdate()
                }
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun mysql(r: DynamicPropertyRegistry) {
            seedLegacy(APP_DB)
            r.add("spring.datasource.url") { url(APP_DB) }
            r.add("spring.datasource.username") { user() }
            r.add("spring.datasource.password") { password() }
        }
    }

    private fun scalar(sql: String, vararg args: Any): Long =
        connect(APP_DB).use { c ->
            c.prepareStatement(sql).run {
                args.forEachIndexed { i, a -> setObject(i + 1, a) }
                executeQuery().run { if (next()) getLong(1) else 0 }
            }
        }

    private fun call(
        method: String,
        path: String,
        token: String?,
        body: String? = null,
        expected: Int = 200,
    ): JsonNode {
        val b =
            when (method) {
                "GET" -> get(path)
                "POST" -> post(path)
                "PATCH" -> patch(path)
                "PUT" -> put(path)
                else -> delete(path)
            }
        token?.let { b.header("Authorization", it) }
        body?.let { b.contentType(MediaType.APPLICATION_JSON).content(it) }
        val text = mvc.perform(b).andExpect(status().`is`(expected)).andReturn().response.contentAsString
        return if (text.isEmpty()) mapper.createObjectNode() else mapper.readTree(text)
    }

    private fun account(role: UserRole = UserRole.USER): Pair<String, User> {
        val key = UUID.randomUUID().toString().take(20)
        val u =
            users.save(
                User(
                    email = "$key@gsm.hs.kr",
                    username = key,
                    nickname = "n-$key",
                    passwordHash = BCryptPasswordEncoder().encode(LEGACY_PASSWORD),
                    cohort = 10,
                    role = role,
                )
            )
        return "Bearer ${tokens.create(u.email, u.tokenVersion)}" to u
    }

    private fun png(): ByteArray =
        ByteArrayOutputStream().use {
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
            it.toByteArray()
        }

    private fun upload(token: String): Long {
        val text =
            mvc.perform(
                    multipart("/api/attachments")
                        .file(MockMultipartFile("file", "a.png", "image/png", png()))
                        .header("Authorization", token)
                )
                .andExpect(status().isCreated)
                .andReturn()
                .response
                .contentAsString
        return mapper.readTree(text)["id"].asLong()
    }

    private fun filePath(attachmentId: Long): Path =
        Path.of(attachmentDir)
            .toAbsolutePath()
            .normalize()
            .resolve(attachments.findById(attachmentId).orElseThrow().storageKey)

    // ---------------------------------------------------------------- 1. 빈 DB 전체 적용

    @Test
    fun `empty MySQL database applies every migration and uses utf8mb4`() {
        val db = "${PREFIX}fresh"
        recreate(db)
        val result = flyway(db).migrate()
        assertTrue(result.success)
        val applied = flyway(db).info().applied().map { it.version.version }
        assertEquals((1..10).map(Int::toString), applied)

        connect(db).use { c ->
            // 한글 seed(V5·V6·V9)가 깨지지 않았는지
            val name =
                c.createStatement()
                    .executeQuery("SELECT name FROM chat_categories WHERE id=1")
                    .apply { next() }
                    .getString(1)
            assertEquals("소프트웨어개발과", name)
            assertEquals(
                83,
                c.createStatement()
                    .executeQuery("SELECT COUNT(*) FROM question_recommendations")
                    .apply { next() }
                    .getInt(1),
            )
            // 1.5 신규 테이블 문자셋이 한글을 저장할 수 있는지
            val nonUtf8 =
                c.prepareStatement(
                        "SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_COLLATION NOT LIKE 'utf8mb4%'"
                    )
                    .apply { setString(1, db) }
                    .executeQuery()
                    .let { rs -> buildList { while (rs.next()) add("${rs.getString(1)}=${rs.getString(2)}") } }
            assertTrue(nonUtf8.isEmpty(), "utf8mb4가 아닌 테이블: $nonUtf8")
        }
    }

    // ---------------------------------------------------------------- 2. V4 데이터 → V5~최신

    @Test
    fun `V4 data survives the upgrade and old login chat and messages keep working`() {
        assertEquals(10L, scalar("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM messages WHERE conversation_id=?", legacyChatId))
        // 기존 사용자는 V10 기본값으로 일반 사용자가 된다(다른 테스트가 만든 운영자는 제외).
        assertEquals(
            2L,
            scalar("SELECT COUNT(*) FROM users WHERE username IN ('legacy-a','legacy-b') AND account_role = 'USER'"),
        )

        val login = call("POST", "/api/login", null, """{"username":"legacy-a","password":"$LEGACY_PASSWORD"}""")
        val token = "Bearer ${login["accessToken"].asText()}"
        val main = call("GET", "/api/main", token)
        assertTrue(main["chatList"].any { it["id"].asLong() == legacyChatId })
        val chat = call("GET", "/api/chats/$legacyChatId", token)
        assertEquals(listOf("기존 질문", "기존 답변"), chat["messages"].map { it["content"].asText() })
        val sent = call("POST", "/api/chats/$legacyChatId/messages", token, """{"content":"업그레이드 후 질문"}""")
        assertTrue(sent["messages"].size() >= 1)
        assertEquals(4L, scalar("SELECT COUNT(*) FROM messages WHERE conversation_id=?", legacyChatId))
        // 기존 사용자도 1.5 기능을 쓸 수 있다
        call("GET", "/api/community/boards", token)
        call("GET", "/api/chat-preferences", token)
    }

    // ---------------------------------------------------------------- 3. 계정·대화 삭제와 외래키

    @Test
    fun `deleting chats and accounts that own every kind of 1_5 data is not blocked by foreign keys`() {
        val (aToken, a) = account()
        val (bToken, _) = account()
        val (adminToken, admin) = account(UserRole.ADMIN)
        val (_, admin2) = account(UserRole.ADMIN)
        val aId = a.id!!

        // A의 대화: 메시지 + 첨부 연결
        val chat = call("POST", "/api/chats", aToken, "{}", 201)["id"].asLong()
        val sentFile = upload(aToken)
        call("POST", "/api/chats/$chat/messages", aToken, """{"content":"첨부 설명","attachmentIds":[$sentFile]}""")
        val chat2 = call("POST", "/api/chats", aToken, "{}", 201)["id"].asLong()
        val file2 = upload(aToken)
        call("POST", "/api/chats/$chat2/messages", aToken, """{"content":"지울 대화","attachmentIds":[$file2]}""")
        upload(aToken) // 전송하지 않은 첨부

        // A의 1.5 데이터
        call("PATCH", "/api/chat-preferences", aToken, """{"departmentId":1,"specialtyId":104}""")
        val policy = call("GET", "/api/privacy/recommendation-consent", aToken)["policyVersion"].asText()
        call("PUT", "/api/privacy/recommendation-consent", aToken, """{"enabled":true,"policyVersion":"$policy"}""")
        call("POST", "/api/setting/inquiry", aToken, """{"content":"문의"}""", 202)
        val board = call("GET", "/api/community/boards", aToken)["items"].first { it["cohort"].isNull }["id"].asLong()
        val aPost =
            call("POST", "/api/community/posts", aToken, """{"boardId":$board,"title":"A 글","content":"본문"}""", 201)["id"]
                .asLong()
        val bPost =
            call("POST", "/api/community/posts", bToken, """{"boardId":$board,"title":"B 글","content":"본문"}""", 201)["id"]
                .asLong()
        call("POST", "/api/community/posts/$bPost/comments", aToken, """{"content":"A 댓글"}""", 201)
        call("POST", "/api/community/posts/$aPost/comments", bToken, """{"content":"B 댓글"}""", 201)
        call("PUT", "/api/community/posts/$bPost/like", aToken)
        call("PUT", "/api/community/posts/$aPost/like", bToken)
        call("POST", "/api/community/reports", aToken, """{"targetType":"POST","targetId":$bPost,"reasonCode":"SPAM"}""", 202)
        val reportOnA =
            call("POST", "/api/community/reports", bToken, """{"targetType":"POST","targetId":$aPost,"reasonCode":"ABUSE"}""", 202)[
                    "id"]
                .asLong()
        call("POST", "/api/admin/community/reports/$reportOnA/actions", adminToken, """{"action":"WARN"}""")
        call("POST", "/api/admin/community/reports/$reportOnA/actions", adminToken, """{"action":"SUSPEND","days":3}""")

        // 대화 하나 삭제: 첨부가 연결된 메시지가 있어도 막히지 않는다
        call("DELETE", "/api/chats/$chat2", aToken, expected = 204)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM chat_attachments WHERE id=?", file2))

        // 계정 삭제
        call("DELETE", "/api/account", aToken, """{"password":"$LEGACY_PASSWORD"}""", 204)
        for ((table, col) in
            listOf(
                "users" to "id",
                "conversations" to "user_id",
                "inquiries" to "user_id",
                "chat_attachments" to "user_id",
                "chat_preferences" to "user_id",
                "recommendation_consents" to "user_id",
                "community_posts" to "author_id",
                "community_comments" to "author_id",
                "community_likes" to "user_id",
                "community_reports" to "reporter_id",
                "community_sanctions" to "user_id",
            )) assertEquals(0L, scalar("SELECT COUNT(*) FROM $table WHERE $col=?", aId), table)
        // A 글에 대한 B의 신고와 증거는 남는다
        assertEquals(1L, scalar("SELECT COUNT(*) FROM community_reports WHERE id=? AND snapshot_title='A 글'", reportOnA))

        // 처리한 운영자 삭제: 처리 기록은 남고 운영자 ID만 비워진다
        users.deleteById(admin.id!!)
        assertEquals(2L, scalar("SELECT COUNT(*) FROM community_moderation_actions WHERE report_id=? AND admin_id IS NULL", reportOnA))
        assertNotNull(admin2.id)
    }

    // ---------------------------------------------------------------- 4. 첨부 파일 정리

    @Test
    fun `attachment cleanup removes only files whose metadata is gone`() {
        val (aToken, a) = account()
        val (bToken, _) = account()
        val aFile = upload(aToken)
        val bUnsent = upload(bToken)
        val bChat = call("POST", "/api/chats", bToken, "{}", 201)["id"].asLong()
        val bSent = upload(bToken)
        call("POST", "/api/chats/$bChat/messages", bToken, """{"content":"첨부","attachmentIds":[$bSent]}""")
        val aPath = filePath(aFile)
        val bPaths = listOf(filePath(bUnsent), filePath(bSent))

        users.deleteById(a.id!!) // DB CASCADE 로 A의 첨부 메타데이터만 사라진다
        assertEquals(0L, scalar("SELECT COUNT(*) FROM chat_attachments WHERE id=?", aFile))
        assertTrue(Files.exists(aPath))

        // 정리 작업은 1시간 이상 지난 파일만 본다
        val old = Instant.now().minusSeconds(7200).toEpochMilli()
        (bPaths + listOf(aPath)).forEach {
            assertTrue(Files.exists(it), "파일 없음: $it")
            assertTrue(it.toFile().setLastModified(old), "수정 시각 변경 실패: $it")
        }
        attachmentService.cleanup()

        assertFalse(Files.exists(aPath), "메타데이터가 없는 A 파일은 지워져야 함")
        bPaths.forEach { assertTrue(Files.exists(it), "B 파일이 지워짐: $it") }
        mvc.perform(get("/api/attachments/$bUnsent/content").header("Authorization", bToken)).andExpect(status().isOk)
    }
}
