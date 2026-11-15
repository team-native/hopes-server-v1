package kr.hs.gsm.hopes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kr.hs.gsm.hopes.domain.User
import kr.hs.gsm.hopes.domain.UserRepository
import kr.hs.gsm.hopes.domain.UserRole
import kr.hs.gsm.hopes.feature.ReportEvidenceRetention
import kr.hs.gsm.hopes.security.AccessTokenService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = ["spring.datasource.url=jdbc:h2:mem:moderation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"]
)
@AutoConfigureMockMvc
class CommunityModerationIntegrationTest
@Autowired
constructor(
    private val mvc: MockMvc,
    private val mapper: ObjectMapper,
    private val users: UserRepository,
    private val tokens: AccessTokenService,
    private val retention: ReportEvidenceRetention,
) {
    @Test
    fun `swagger exposes moderation contracts and UI without authentication`() {
        val spec = call("GET", "/v3/api-docs", null)
        val paths = spec["paths"]
        assertTrue(paths["/api/admin/community/reports"].has("get"))
        assertTrue(paths["/api/admin/community/reports/{reportId}"].has("get"))
        val action = paths["/api/admin/community/reports/{reportId}/actions"]["post"]
        assertEquals("커뮤니티 운영자", action["tags"][0].asText())
        assertTrue(action["security"].any { it.has("bearerAuth") })
        val request = spec["components"]["schemas"]["ModerationRequest"]
        assertTrue(request["required"].any { it.asText() == "action" })
        assertTrue(request["properties"].has("days"))
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk)
    }

    private class Account(val token: String, val user: User)

    private fun account(role: UserRole = UserRole.USER): Account {
        val key = UUID.randomUUID().toString()
        val u =
            users.save(
                User(
                    email = "$key@gsm.hs.kr",
                    username = key,
                    nickname = "private-$key",
                    passwordHash = "unused",
                    cohort = 10,
                    role = role,
                )
            )
        return Account("Bearer ${tokens.create(u.email, u.tokenVersion)}", u)
    }

    private fun call(
        method: String,
        path: String,
        token: String?,
        body: String? = null,
        expected: Int = 200,
    ): JsonNode {
        val builder =
            when (method) {
                "GET" -> get(path)
                "POST" -> post(path)
                "PATCH" -> patch(path)
                "PUT" -> put(path)
                else -> delete(path)
            }
        token?.let { builder.header("Authorization", it) }
        body?.let { builder.contentType(MediaType.APPLICATION_JSON).content(it) }
        val response =
            mvc.perform(builder)
                .andExpect(status().`is`(expected))
                .andReturn()
                .response
                .contentAsString
        return if (response.isEmpty()) mapper.createObjectNode() else mapper.readTree(response)
    }

    private fun generalBoard(token: String) =
        call("GET", "/api/community/boards", token)["items"]
            .first { it["cohort"].isNull }["id"]
            .asLong()

    private fun writePost(token: String, title: String = "제목", content: String = "본문") =
        call(
                "POST",
                "/api/community/posts",
                token,
                """{"boardId":${generalBoard(token)},"title":"$title","content":"$content"}""",
                201,
            )["id"]
            .asLong()

    private fun report(token: String, type: String, id: Long) =
        call(
                "POST",
                "/api/community/reports",
                token,
                """{"targetType":"$type","targetId":$id,"reasonCode":"ABUSE","detail":"욕설"}""",
                202,
            )["id"]
            .asLong()

    private fun act(admin: String, reportId: Long, body: String, expected: Int = 200) =
        call("POST", "/api/admin/community/reports/$reportId/actions", admin, body, expected)

    @Test
    fun `operator API rejects anonymous and ordinary users and follows role changes immediately`() {
        val normal = account()
        val admin = account(UserRole.ADMIN)
        call("GET", "/api/admin/community/reports", null, expected = 401)
        val denied = call("GET", "/api/admin/community/reports", normal.token, expected = 403)
        assertEquals("FORBIDDEN", denied["code"].asText())
        call("GET", "/api/admin/community/reports/1", normal.token, expected = 403)
        act(normal.token, 1, """{"action":"HIDE"}""", 403)
        call("GET", "/api/admin/community/reports", admin.token)

        // 운영자 권한을 회수하면 같은 토큰이어도 다음 요청부터 막힌다.
        admin.user.role = UserRole.USER
        users.save(admin.user)
        call("GET", "/api/admin/community/reports", admin.token, expected = 403)
    }

    @Test
    fun `report keeps evidence after the author edits or deletes and never exposes identities`() {
        val author = account()
        val reporter = account()
        val admin = account(UserRole.ADMIN)
        val postId = writePost(author.token, "원래 제목", "원래 본문")
        val reportId = report(reporter.token, "POST", postId)
        call("PATCH", "/api/community/posts/$postId", author.token, """{"content":"고친 본문"}""")
        call("DELETE", "/api/community/posts/$postId", author.token, expected = 204)

        val open = call("GET", "/api/admin/community/reports?status=RECEIVED&size=100", admin.token)
        assertTrue(open["items"].any { it["id"].asLong() == reportId })
        call("GET", "/api/admin/community/reports?status=WRONG", admin.token, expected = 400)

        val detail = call("GET", "/api/admin/community/reports/$reportId", admin.token)
        assertEquals("원래 제목", detail["evidence"]["title"].asText())
        assertEquals("원래 본문", detail["evidence"]["content"].asText())
        assertEquals("욕설", detail["detail"].asText())
        assertTrue(detail["currentTarget"]["deleted"].asBoolean())
        assertEquals("", detail["currentTarget"]["content"].asText())

        val raw = detail.toString()
        for (u in listOf(author.user, reporter.user)) assertFalse(raw.contains(u.username))
        for (key in listOf("reporterId", "authorId", "userId", "email", "nickname"))
            assertFalse(detail.has(key), key)
        call("GET", "/api/admin/community/reports/999999", admin.token, expected = 404)
    }

    @Test
    fun `hidden posts and comments disappear for users and come back on restore`() {
        val author = account()
        val reporter = account()
        val admin = account(UserRole.ADMIN)
        val marker = UUID.randomUUID().toString()
        val postId = writePost(author.token, marker)
        val postReport = report(reporter.token, "POST", postId)

        act(admin.token, postReport, """{"action":"HIDE","memo":"욕설 확인"}""")
        call("GET", "/api/community/posts/$postId", reporter.token, expected = 404)
        call("GET", "/api/community/posts/$postId", author.token, expected = 404)
        assertEquals(0, call("GET", "/api/community/posts?keyword=$marker", reporter.token)["items"].size())
        assertTrue(
            call("GET", "/api/community/me/posts", author.token)["items"].none {
                it["id"].asLong() == postId
            }
        )
        call("PUT", "/api/community/posts/$postId/like", reporter.token, expected = 404)
        call("POST", "/api/community/posts/$postId/comments", reporter.token, """{"content":"댓글"}""", 404)
        // 숨겨도 운영자는 내용을 그대로 본다.
        val hidden = call("GET", "/api/admin/community/reports/$postReport", admin.token)
        assertTrue(hidden["currentTarget"]["hidden"].asBoolean())
        assertEquals(marker, hidden["currentTarget"]["title"].asText())

        act(admin.token, postReport, """{"action":"RESTORE"}""")
        call("GET", "/api/community/posts/$postId", reporter.token)
        assertEquals(1, call("GET", "/api/community/posts?keyword=$marker", reporter.token)["items"].size())

        val commentId =
            call("POST", "/api/community/posts/$postId/comments", author.token, """{"content":"나쁜 댓글"}""", 201)[
                    "id"]
                .asLong()
        val commentReport = report(reporter.token, "COMMENT", commentId)
        val commentDetail = call("GET", "/api/admin/community/reports/$commentReport", admin.token)
        assertEquals(postId, commentDetail["targetPostId"].asLong())
        assertEquals("나쁜 댓글", commentDetail["evidence"]["content"].asText())
        act(admin.token, commentReport, """{"action":"HIDE"}""")
        assertEquals(0, call("GET", "/api/community/posts/$postId/comments", reporter.token)["items"].size())
        assertEquals(0, call("GET", "/api/community/posts/$postId", reporter.token)["commentCount"].asInt())
        call("PATCH", "/api/community/comments/$commentId", author.token, """{"content":"수정"}""", 404)
    }

    @Test
    fun `sanctions limit writing and every action is recorded in order`() {
        val author = account()
        val reporter = account()
        val admin = account(UserRole.ADMIN)
        val postId = writePost(author.token)
        val reportId = report(reporter.token, "POST", postId)

        act(admin.token, reportId, """{"action":"WARN","memo":"1차 경고"}""")
        writePost(author.token) // 경고만으로는 막지 않는다.

        act(admin.token, reportId, """{"action":"SUSPEND"}""", 400)
        act(admin.token, reportId, """{"action":"SUSPEND","days":0}""", 400)
        act(admin.token, reportId, """{"action":"SUSPEND","days":366}""", 400)
        act(admin.token, reportId, """{"action":"BAN"}""", 400)
        act(admin.token, reportId, """{"action":"SUSPEND","days":7,"memo":"반복 욕설"}""")

        val board = generalBoard(author.token)
        call("POST", "/api/community/posts", author.token, """{"boardId":$board,"title":"t","content":"c"}""", 403)
        call("POST", "/api/community/posts/$postId/comments", author.token, """{"content":"c"}""", 403)
        call("PATCH", "/api/community/posts/$postId", author.token, """{"content":"c"}""", 403)
        call("GET", "/api/community/posts/$postId", author.token) // 열람은 가능
        writePost(reporter.token) // 다른 사용자는 영향 없음

        val resolved = act(admin.token, reportId, """{"action":"RESOLVE","memo":"처리 완료"}""")
        assertEquals("RESOLVED", resolved["status"].asText())
        assertFalse(resolved["resolvedAt"].isNull)
        act(admin.token, reportId, """{"action":"RESOLVE"}""", 409)
        act(admin.token, reportId, """{"action":"DISMISS"}""", 409)
        val reopened = act(admin.token, reportId, """{"action":"REOPEN"}""")
        assertEquals("RECEIVED", reopened["status"].asText())
        assertTrue(reopened["resolvedAt"].isNull)
        act(admin.token, reportId, """{"action":"REOPEN"}""", 409)

        assertEquals(2, reopened["authorSanctionCount"].asInt())
        assertEquals(
            listOf("WARN", "SUSPEND", "RESOLVE", "REOPEN"),
            reopened["actions"].map { it["action"].asText() },
        )
        assertEquals("반복 욕설", reopened["actions"][1]["memo"].asText())
        assertEquals(admin.user.id, reopened["actions"][0]["adminId"].asLong())
        // 실패한 요청(400/409)은 기록되지 않는다.
        assertEquals(4, reopened["actions"].size())
    }

    @Test
    fun `evidence is purged after retention and deleting accounts is not blocked`() {
        val author = account()
        val reporter = account()
        val admin = account(UserRole.ADMIN)
        val otherAdmin = account(UserRole.ADMIN)
        val closedReport = report(reporter.token, "POST", writePost(author.token, "닫힌 신고"))
        val openReport = report(reporter.token, "POST", writePost(author.token, "열린 신고"))
        act(admin.token, closedReport, """{"action":"SUSPEND","days":3}""")
        act(admin.token, closedReport, """{"action":"RESOLVE"}""")

        // 보관 기간(기본 365일) 안이면 지우지 않는다.
        retention.purge(Instant.now().plus(300, ChronoUnit.DAYS))
        assertFalse(call("GET", "/api/admin/community/reports/$closedReport", admin.token)["evidence"]["content"].isNull)

        retention.purge(Instant.now().plus(366, ChronoUnit.DAYS))
        val purged = call("GET", "/api/admin/community/reports/$closedReport", admin.token)
        assertTrue(purged["evidence"]["content"].isNull)
        assertTrue(purged["evidence"]["title"].isNull)
        assertFalse(purged["evidence"]["purgedAt"].isNull)
        assertEquals("", purged["detail"].asText())
        act(admin.token, closedReport, """{"action":"REOPEN"}""", 409)
        // 처리 전 신고는 기간과 상관없이 남는다.
        val stillOpen = call("GET", "/api/admin/community/reports/$openReport", admin.token)
        assertEquals("열린 신고", stillOpen["evidence"]["title"].asText())

        // 제재받은 작성자 탈퇴: 글·제재는 함께 지워지고 신고 증거는 남는다.
        users.delete(author.user)
        val afterAuthor = call("GET", "/api/admin/community/reports/$openReport", admin.token)
        assertFalse(afterAuthor["currentTarget"]["exists"].asBoolean())
        assertEquals("열린 신고", afterAuthor["evidence"]["title"].asText())
        assertEquals(0, afterAuthor["authorSanctionCount"].asInt())
        act(admin.token, openReport, """{"action":"HIDE"}""", 409)

        // 처리한 운영자 탈퇴: 처리 기록은 남고 운영자 ID만 비워진다.
        users.delete(admin.user)
        val afterAdmin = call("GET", "/api/admin/community/reports/$closedReport", otherAdmin.token)
        assertEquals(2, afterAdmin["actions"].size())
        assertTrue(afterAdmin["actions"].all { it["adminId"].isNull })
    }
}
