package kr.hs.gsm.hopes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kr.hs.gsm.hopes.ai.FakeGeminiClient
import kr.hs.gsm.hopes.ai.RagIndexService
import kr.hs.gsm.hopes.domain.User
import kr.hs.gsm.hopes.domain.UserRepository
import kr.hs.gsm.hopes.feature.AttachmentRepository
import kr.hs.gsm.hopes.feature.CategoryRepository
import kr.hs.gsm.hopes.security.AccessTokenService
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties =
        [
            "spring.datasource.url=jdbc:h2:mem:v15;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "hopes.ai.enabled=true",
            "hopes.ai.chunks-path=classpath:data/test_rag_chunks.jsonl",
            "hopes.ai.cache-path=./target/v15/embedding.json",
            "hopes.attachments.directory=./target/v15/files",
        ]
)
@AutoConfigureMockMvc
@Import(HopesAiIntegrationTest.FakeAiConfig::class)
class V15ApiIntegrationTest
@Autowired
constructor(
    private val mvc: MockMvc,
    private val mapper: ObjectMapper,
    private val users: UserRepository,
    private val tokens: AccessTokenService,
    private val files: AttachmentRepository,
    private val categories: CategoryRepository,
    private val ai: FakeGeminiClient,
    private val rag: RagIndexService,
) {
    private fun user(cohort: Int = 10): String = account(cohort).first

    /** 토큰과 함께 이메일·아이디·닉네임에 공통으로 들어가는 고유 키를 돌려준다. */
    private fun account(cohort: Int = 10): Pair<String, String> {
        val key = UUID.randomUUID().toString()
        val u =
            users.save(
                User(
                    email = "$key@gsm.hs.kr",
                    username = key,
                    nickname = "private-$key",
                    passwordHash = "unused",
                    cohort = cohort,
                )
            )
        return "Bearer ${tokens.create(u.email,u.tokenVersion)}" to key
    }

    private fun boardId(token: String, cohort: Int?) =
        call("GET", "/api/community/boards", token)["items"]
            .first { if (cohort == null) it["cohort"].isNull else it["cohort"].asInt() == cohort }[
                "id"]
            .asLong()

    private fun raw(path: String, token: String) =
        mvc.perform(get(path).header("Authorization", token))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

    private fun call(
        method: String,
        path: String,
        token: String,
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
        builder.header("Authorization", token)
        body?.let { builder.contentType(MediaType.APPLICATION_JSON).content(it) }
        val response =
            mvc.perform(builder)
                .andExpect(status().`is`(expected))
                .andReturn()
                .response
                .contentAsString
        return if (response.isEmpty()) mapper.createObjectNode() else mapper.readTree(response)
    }

    private fun createChat(token: String) =
        call("POST", "/api/chats", token, "{}", 201)["id"].asLong()

    private fun image() =
        ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output)
            output.toByteArray()
        }

    private fun upload(
        token: String,
        bytes: ByteArray = image(),
        name: String = "test.png",
        mime: String = "image/png",
        expected: Int = 201,
    ): JsonNode {
        val response =
            mvc.perform(
                    multipart("/api/attachments")
                        .file(MockMultipartFile("file", name, mime, bytes))
                        .header("Authorization", token)
                )
                .andExpect(status().`is`(expected))
                .andReturn()
                .response
                .contentAsString
        return mapper.readTree(response)
    }

    @Test
    fun `new endpoints require authentication and PUT is allowed through CORS`() {
        for (path in
            listOf(
                "/api/departments",
                "/api/specialties",
                "/api/chat-preferences",
                "/api/question-recommendations",
                "/api/privacy/recommendation-consent",
                "/api/community/boards",
                "/api/community/posts",
                "/api/community/me/posts",
                "/api/community/me/comments",
                "/api/campus/map",
                "/api/campus/navigation/places",
                "/api/attachments/1",
            )) mvc.perform(get(path)).andExpect(status().isUnauthorized)
        mvc.perform(
                options("/api/privacy/recommendation-consent")
                    .header("Origin", "http://localhost:3000")
                    .header("Access-Control-Request-Method", "PUT")
            )
            .andExpect(status().isOk)
    }

    @Test
    fun `OpenAPI exposes all 34 contracts including existing chat extensions`() {
        val paths =
            mapper
                .readTree(mvc.perform(get("/v3/api-docs")).andReturn().response.contentAsString)[
                    "paths"]
        val contracts =
            mapOf(
                "/api/attachments" to "post",
                "/api/attachments/{attachmentId}" to "get delete",
                "/api/attachments/{attachmentId}/content" to "get",
                "/api/chats/{id}/messages" to "post",
                "/api/chats/{id}" to "get",
                "/api/departments" to "get",
                "/api/specialties" to "get",
                "/api/chat-preferences" to "get patch",
                "/api/question-recommendations" to "get",
                "/api/privacy/recommendation-consent" to "get put",
                "/api/community/boards" to "get",
                "/api/community/posts" to "get post",
                "/api/community/posts/{postId}" to "get patch delete",
                "/api/community/posts/{postId}/comments" to "get post",
                "/api/community/comments/{commentId}" to "patch delete",
                "/api/community/posts/{postId}/like" to "put delete",
                "/api/community/reports" to "post",
                "/api/community/me/posts" to "get",
                "/api/community/me/comments" to "get",
                "/api/campus/map" to "get",
                "/api/campus/location-check" to "post",
                "/api/chats/{id}/location-check" to "post",
                "/api/campus/buildings/{buildingId}/floor-plans" to "get",
                "/api/campus/navigation/places" to "get",
                "/api/campus/navigation/route" to "get",
            )
        assertEquals(34, contracts.values.sumOf { it.split(' ').size })
        contracts.forEach { (path, methods) ->
            methods.split(' ').forEach { assertTrue(paths[path]?.has(it) == true, "$it $path") }
        }
    }

    @Test
    fun `concurrent likes remain unique and post search treats wildcard characters literally`() {
        val t = user()
        val board =
            call("GET", "/api/community/boards", t)["items"]
                .first { it["cohort"].isNull }["id"]
                .asLong()
        val marker = UUID.randomUUID().toString()
        val id =
            call(
                    "POST",
                    "/api/community/posts",
                    t,
                    """{"boardId":$board,"title":"$marker%","content":"질문"}""",
                    201,
                )["id"]
                .asLong()
        val pool = Executors.newFixedThreadPool(3)
        try {
            val jobs =
                (1..3).map {
                    pool.submit<Long> {
                        call("PUT", "/api/community/posts/$id/like", t)["likeCount"].asLong()
                    }
                }
            jobs.forEach { assertEquals(1L, it.get(10, TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
        }
        val searchResult =
            mvc.perform(
                    get("/api/community/posts")
                        .param("keyword", "$marker%")
                        .header("Authorization", t)
                )
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        assertEquals(1, mapper.readTree(searchResult)["items"].size())
        call("GET", "/api/community/posts?page=-1", t, expected = 400)
        call(
            "POST",
            "/api/community/posts",
            t,
            """{"boardId":$board,"title":" ","content":"본문"}""",
            400,
        )
    }

    @Test
    fun `catalog selections persist independently and null clears only specified field`() {
        val t = user()
        assertEquals(3, call("GET", "/api/departments", t)["items"].size())
        assertEquals(9, call("GET", "/api/specialties", t)["items"].size())
        call("PATCH", "/api/chat-preferences", t, """{"departmentId":2,"specialtyId":101}""")
        val patched = call("PATCH", "/api/chat-preferences", t, """{"departmentId":null}""")
        assertTrue(patched["departmentId"].isNull)
        assertEquals(101, patched["specialtyId"].asInt())
        assertEquals(101, call("GET", "/api/chat-preferences", t)["specialtyId"].asInt())
        call("PATCH", "/api/chat-preferences", t, """{"specialtyId":"bad"}""", 400)
        call("PATCH", "/api/chat-preferences", t, """{"departmentId":101}""", 409)
    }

    @Test
    fun `recommendations refresh with exclusions and personalization follows consent`() {
        val t = user()
        val defaults = call("GET", "/api/question-recommendations?limit=2", t)
        assertEquals("DEFAULT", defaults["source"].asText())
        val chosen = call("GET", "/api/question-recommendations?departmentId=1&specialtyId=104", t)
        assertEquals("COMBINED", chosen["source"].asText())
        assertEquals(201, chosen["items"][0]["id"].asInt())
        val ids = defaults["items"].map { it["id"].asLong() }
        val refreshed =
            call(
                "GET",
                "/api/question-recommendations?limit=2&excludeIds=${ids.joinToString(",")}&refreshToken=${defaults["nextRefreshToken"].asText()}",
                t,
            )
        assertTrue(refreshed["items"].none { it["id"].asLong() in ids })
        assertFalse(call("GET", "/api/privacy/recommendation-consent", t)["enabled"].asBoolean())
        assertEquals(
            "DEFAULT",
            call("GET", "/api/question-recommendations?personalized=true", t)["source"].asText(),
        )
        call(
            "PUT",
            "/api/privacy/recommendation-consent",
            t,
            """{"enabled":true,"policyVersion":"old"}""",
            409,
        )
        call(
            "PUT",
            "/api/privacy/recommendation-consent",
            t,
            """{"enabled":true,"policyVersion":"1"}""",
        )
        assertEquals(
            "PERSONALIZED",
            call("GET", "/api/question-recommendations?personalized=true", t)["source"].asText(),
        )
        call(
            "PUT",
            "/api/privacy/recommendation-consent",
            t,
            """{"enabled":false,"policyVersion":"1"}""",
        )
        assertEquals(
            "DEFAULT",
            call("GET", "/api/question-recommendations?personalized=true", t)["source"].asText(),
        )
        call("GET", "/api/question-recommendations?limit=1000", t, expected = 400)
    }

    @Test
    fun `community allows other cohorts and never serializes authors`() {
        val owner = user(10)
        val other = user(8)
        val board =
            call("GET", "/api/community/boards", other)["items"]
                .first { it["cohort"].asInt() == 8 }["id"]
                .asLong()
        val p =
            call(
                "POST",
                "/api/community/posts",
                owner,
                """{"boardId":$board,"title":"질문%제목","content":"준비물 질문"}""",
                201,
            )
        val id = p["id"].asLong()
        val view = call("GET", "/api/community/posts/$id", other)
        assertFalse(view["isMine"].asBoolean())
        assertEquals("익명", view["authorLabel"].asText())
        for (key in
            listOf("authorId", "userId", "email", "nickname", "username", "cohort")) assertFalse(
            view.has(key),
            key,
        )
        call("PATCH", "/api/community/posts/$id", other, """{"title":"탈취"}""", 403)
        call("DELETE", "/api/community/posts/$id", other, expected = 403)
        call("PATCH", "/api/community/posts/$id", owner, """{"content":"수정된 질문"}""")
        val c =
            call("POST", "/api/community/posts/$id/comments", other, """{"content":"댓글"}""", 201)[
                    "id"]
                .asLong()
        call("PATCH", "/api/community/comments/$c", owner, """{"content":"탈취"}""", 403)
        call("PATCH", "/api/community/comments/$c", other, """{"content":"수정댓글"}""")
        assertEquals(1, call("GET", "/api/community/posts/$id/comments", owner)["items"].size())
        assertEquals(1, call("GET", "/api/community/me/comments", other)["items"].size())
        repeat(2) {
            assertEquals(
                1,
                call("PUT", "/api/community/posts/$id/like", other)["likeCount"].asInt(),
            )
        }
        repeat(2) {
            assertEquals(
                0,
                call("DELETE", "/api/community/posts/$id/like", other)["likeCount"].asInt(),
            )
        }
        val report = """{"targetType":"POST","targetId":$id,"reasonCode":"SPAM"}"""
        call("POST", "/api/community/reports", other, report, 202)
        call("POST", "/api/community/reports", other, report, 409)
        assertEquals(1, call("GET", "/api/community/me/posts", owner)["items"].size())
        assertEquals(0, call("GET", "/api/community/me/posts", other)["items"].size())
        call("DELETE", "/api/community/comments/$c", other, expected = 204)
        assertEquals(0, call("GET", "/api/community/posts/$id", owner)["commentCount"].asInt())
        call("DELETE", "/api/community/posts/$id", owner, expected = 204)
        call("GET", "/api/community/posts/$id", owner, expected = 404)
    }

    @Test
    fun `every community read response hides author identity and author cohort`() {
        val (owner, ownerKey) = account(10)
        val (other, otherKey) = account(8)
        val board8 = boardId(owner, 8)
        val board10 = boardId(owner, 10)
        val marker = UUID.randomUUID().toString()
        val postId =
            call(
                    "POST",
                    "/api/community/posts",
                    owner,
                    """{"boardId":$board8,"title":"$marker","content":"본문"}""",
                    201,
                )["id"]
                .asLong()
        call("POST", "/api/community/posts/$postId/comments", other, """{"content":"댓글"}""", 201)

        // 게시판 분류는 글을 올린 게시판(8기)을 따르고 작성자 기수(10기)와 무관하다.
        assertEquals(board8, call("GET", "/api/community/posts/$postId", other)["boardId"].asLong())
        val inBoard8 = call("GET", "/api/community/posts?boardId=$board8&keyword=$marker", other)
        assertEquals(listOf(postId), inBoard8["items"].map { it["id"].asLong() })
        val inBoard10 = call("GET", "/api/community/posts?boardId=$board10&keyword=$marker", other)
        assertEquals(0, inBoard10["items"].size())

        val responses =
            mapOf(
                "list" to raw("/api/community/posts?boardId=$board8", other),
                "search" to raw("/api/community/posts?keyword=$marker", other),
                "detail" to raw("/api/community/posts/$postId", other),
                "comments" to raw("/api/community/posts/$postId/comments", owner),
                "myPosts" to raw("/api/community/me/posts", owner),
                "myComments" to raw("/api/community/me/comments", other),
            )
        val forbiddenKeys =
            listOf("authorId", "userId", "email", "nickname", "username", "cohort", "studentNumber")
        for ((name, body) in responses) {
            assertFalse(body.contains(ownerKey), "$name 응답에 작성자 식별값이 노출됨")
            assertFalse(body.contains(otherKey), "$name 응답에 댓글 작성자 식별값이 노출됨")
            for (key in forbiddenKeys) assertFalse(body.contains("\"$key\""), "$name 응답에 $key 필드가 있음")
        }
    }

    @Test
    fun `other users cannot delete comments and post pages are ordered and bounded`() {
        val owner = user(10)
        val other = user(10)
        val board = boardId(owner, null)
        val marker = UUID.randomUUID().toString()
        val ids =
            (1..3).map {
                call(
                        "POST",
                        "/api/community/posts",
                        owner,
                        """{"boardId":$board,"title":"$marker-$it","content":"본문"}""",
                        201,
                    )["id"]
                    .asLong()
            }
        val c =
            call("POST", "/api/community/posts/${ids[0]}/comments", owner, """{"content":"내 댓글"}""", 201)[
                    "id"]
                .asLong()
        call("DELETE", "/api/community/comments/$c", other, expected = 403)
        assertEquals(1, call("GET", "/api/community/posts/${ids[0]}/comments", other)["items"].size())
        call("DELETE", "/api/community/comments/$c", owner, expected = 204)
        call("DELETE", "/api/community/comments/$c", owner, expected = 404)
        call("PATCH", "/api/community/comments/$c", owner, """{"content":"부활"}""", 404)

        val first = call("GET", "/api/community/posts?keyword=$marker&page=0&size=2", other)
        assertEquals(ids.reversed().take(2), first["items"].map { it["id"].asLong() })
        assertTrue(first["hasNext"].asBoolean())
        val second = call("GET", "/api/community/posts?keyword=$marker&page=1&size=2", other)
        assertEquals(listOf(ids[0]), second["items"].map { it["id"].asLong() })
        assertFalse(second["hasNext"].asBoolean())
        call("GET", "/api/community/posts?size=0", other, expected = 400)
        call("GET", "/api/community/posts?size=101", other, expected = 400)
        call("GET", "/api/community/posts?size=100", other)
        call("GET", "/api/community/posts/${ids[0]}/comments?size=101", other, expected = 400)
        call("GET", "/api/community/me/posts?page=-1", owner, expected = 400)
    }

    @Test
    fun `image and PDF validation ownership expiry and deletion`() {
        val owner = user()
        val other = user()
        val uploaded = upload(owner)
        val id = uploaded["id"].asLong()
        assertEquals("READY", uploaded["status"].asText())
        call("GET", "/api/attachments/$id", other, expected = 404)
        mvc.perform(get("/api/attachments/$id/content").header("Authorization", owner))
            .andExpect(status().isOk)
        call("DELETE", "/api/attachments/$id", other, expected = 404)
        call("DELETE", "/api/attachments/$id", owner, expected = 204)
        upload(owner, "fake".toByteArray(), expected = 400)
        upload(owner, image(), "fake.pdf", "application/pdf", 400)
        upload(owner, image(), "fake.gif", "image/gif", 415)
        val protected =
            ByteArrayOutputStream().use { out ->
                PDDocument().use { doc ->
                    doc.addPage(PDPage())
                    doc.protect(
                        StandardProtectionPolicy(
                            "owner-secret",
                            "reader-secret",
                            AccessPermission(),
                        )
                    )
                    doc.save(out)
                }
                out.toByteArray()
            }
        upload(owner, protected, "protected.pdf", "application/pdf", 400)
        val pdf =
            ByteArrayOutputStream().use { out ->
                PDDocument().use {
                    it.addPage(PDPage())
                    it.save(out)
                }
                out.toByteArray()
            }
        val pdfId = upload(owner, pdf, "test.pdf", "application/pdf")["id"].asLong()
        val row = files.findById(pdfId).orElseThrow()
        row.expiresAt = Instant.now().minusSeconds(1)
        files.save(row)
        assertEquals("EXPIRED", call("GET", "/api/attachments/$pdfId", owner)["status"].asText())
        call("GET", "/api/attachments/$pdfId/content", owner, expected = 410)
    }

    @Test
    fun `multimodal bytes reach AI failed attempts remain retryable and successful requests are idempotent`() {
        val t = user()
        val chat = createChat(t)
        val file = upload(t)["id"].asLong()
        val request =
            """{"content":"첨부 내용 설명해줘","attachmentIds":[$file],"departmentId":1,"specialtyId":104,"clientRequestId":"test-retry"}"""
        ai.generateError = IllegalStateException("fake failure")
        try {
            call("POST", "/api/chats/$chat/messages", t, request, 502)
        } finally {
            ai.generateError = null
        }
        assertTrue(files.findById(file).orElseThrow().messageId == null)
        ai.answer = "## 분석\n\n**이미지** 내용\n\n```kotlin\nval a = 1\n```"
        val response = call("POST", "/api/chats/$chat/messages", t, request)
        assertEquals(2, response["messages"].size())
        assertEquals(file, response["messages"][0]["attachments"][0]["id"].asLong())
        assertEquals("MARKDOWN", response["messages"][1]["contentFormat"].asText())
        assertEquals(ai.answer, response["messages"][1]["content"].asText())
        assertArrayEquals(image(), ai.mediaRequests.last().last().bytes)
        assertTrue(ai.generatedTurns.last().last().second.contains("선택 학과: 소프트웨어개발과"))
        assertEquals(2, call("POST", "/api/chats/$chat/messages", t, request)["messages"].size())
        call("POST", "/api/chats/$chat/messages", t, request.replace("첨부 내용 설명해줘", "다른 질문"), 409)
        call("DELETE", "/api/attachments/$file", t, expected = 409)
        assertEquals(
            file,
            call("GET", "/api/chats/$chat", t)["messages"][0]["attachments"][0]["id"].asLong(),
        )
        call(
            "POST",
            "/api/chats/$chat/messages",
            t,
            """{"content":"재사용","attachmentIds":[$file]}""",
            409,
        )
    }

    @Test
    fun `location rejects invalid coordinates and unregistered campus returns unavailable`() {
        val t = user()
        val chat = createChat(t)
        call("GET", "/api/campus/map", t, expected = 503)
        call("GET", "/api/campus/navigation/places", t, expected = 503)
        val now = Instant.now()
        call(
            "POST",
            "/api/campus/location-check",
            t,
            """{"latitude":91,"longitude":127,"horizontalAccuracyMeters":10,"measuredAt":"$now"}""",
            400,
        )
        val valid =
            """{"latitude":35,"longitude":127,"horizontalAccuracyMeters":10,"measuredAt":"$now"}"""
        call("POST", "/api/chats/$chat/location-check", t, valid, 503)
        assertEquals(0, call("GET", "/api/chats/$chat", t)["messages"].size())
        call("POST", "/api/chats/$chat/location-check", user(), valid, 404)
    }
}
