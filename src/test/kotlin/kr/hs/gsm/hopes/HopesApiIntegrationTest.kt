package kr.hs.gsm.hopes

import com.fasterxml.jackson.databind.ObjectMapper
import kr.hs.gsm.hopes.domain.EmailVerificationRepository
import kr.hs.gsm.hopes.domain.ChatMessageRepository
import kr.hs.gsm.hopes.domain.ConversationRepository
import kr.hs.gsm.hopes.domain.InquiryRepository
import kr.hs.gsm.hopes.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

@SpringBootTest(
    properties = [
        "hopes.mail.enabled=false",
        "hopes.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:hopes-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    ]
)
@AutoConfigureMockMvc
class HopesApiIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val verificationRepository: EmailVerificationRepository,
    private val inquiryRepository: InquiryRepository,
    private val userRepository: UserRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: ChatMessageRepository,
) {
    @Test
    fun `swagger exposes every API and bearer authentication`() {
        val result = mockMvc.get("/v3/api-docs")
            .andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
                jsonPath("$.info.title") { value("Hopes API") }
                jsonPath("$.components.securitySchemes.bearerAuth") { exists() }
            }
            .andReturn()

        val paths = objectMapper.readTree(result.response.contentAsString)["paths"]
        val expectedPaths = setOf(
            "/api/signup/email-verifications",
            "/api/signup/email-verifications/confirm",
            "/api/signup",
            "/api/login",
            "/api/password/request",
            "/api/password/reset",
            "/api/logout",
            "/api/account",
            "/api/main",
            "/api/chats",
            "/api/chats/{id}",
            "/api/chats/{id}/messages",
            "/api/general",
            "/api/mypage",
            "/api/setting/main",
            "/api/setting",
            "/api/setting/inquiry",
        )
        assertEquals(expectedPaths, paths.fieldNames().asSequence().toSet())
        assert(paths["/api/chats/{id}"].has("delete"))
    }

    @Test
    fun `앱 헤더로 질문 출처를 기록한다`() {
        val authorization = signupAndAuthorization("monitor-user@gsm.hs.kr", "monitor-user")
        val chatResult = mockMvc.post("/api/chats") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"출처 확인"}"""
        }.andExpect { status { isCreated() } }.andReturn()
        val chatId = objectMapper.readTree(chatResult.response.contentAsString)["id"].asLong()

        mockMvc.post("/api/chats/$chatId/messages") {
            header("Authorization", authorization)
            header("X-Hopes-Client", "APP")
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"앱에서 보낸 질문"}"""
        }.andExpect { status { isOk() } }

        val saved = messageRepository.findAllByConversationIdOrderByCreatedAtAscIdAsc(chatId).single()
        assertEquals("APP", saved.clientPlatform.name)
    }

    @Test
    fun `헤더가 없으면 브라우저 User-Agent를 웹으로 판별한다`() {
        val authorization = signupAndAuthorization("web-monitor@gsm.hs.kr", "web-monitor")
        val chatResult = mockMvc.post("/api/chats") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"웹 출처 확인"}"""
        }.andExpect { status { isCreated() } }.andReturn()
        val chatId = objectMapper.readTree(chatResult.response.contentAsString)["id"].asLong()

        mockMvc.post("/api/chats/$chatId/messages") {
            header("Authorization", authorization)
            header("User-Agent", "Mozilla/5.0 Chrome/140.0")
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"웹에서 보낸 질문"}"""
        }.andExpect { status { isOk() } }

        val saved = messageRepository.findAllByConversationIdOrderByCreatedAtAscIdAsc(chatId).single()
        assertEquals("WEB", saved.clientPlatform.name)
    }

    @Test
    fun `사용자는 본인의 대화 하나만 삭제할 수 있다`() {
        val ownerEmail = "chat-owner@gsm.hs.kr"
        val ownerAuthorization = signupAndAuthorization(ownerEmail, "chat-owner")
        val otherAuthorization = signupAndAuthorization("chat-other@gsm.hs.kr", "chat-other")

        val chatResult = mockMvc.post("/api/chats") {
            header("Authorization", ownerAuthorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"개별 삭제할 대화"}"""
        }.andExpect { status { isCreated() } }.andReturn()
        val chatId = objectMapper.readTree(chatResult.response.contentAsString)["id"].asLong()
        mockMvc.post("/api/chats/$chatId/messages") {
            header("Authorization", ownerAuthorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"함께 삭제할 메시지"}"""
        }.andExpect { status { isOk() } }

        mockMvc.delete("/api/chats/$chatId") {
            header("Authorization", otherAuthorization)
        }.andExpect { status { isNotFound() } }
        assert(conversationRepository.existsById(chatId))

        mockMvc.delete("/api/chats/$chatId") {
            header("Authorization", ownerAuthorization)
        }.andExpect { status { isNoContent() } }

        assert(!conversationRepository.existsById(chatId))
        assertEquals(0, messageRepository.findAllByConversationIdOrderByCreatedAtAscIdAsc(chatId).size)
        mockMvc.get("/api/chats/$chatId") {
            header("Authorization", ownerAuthorization)
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `account deletion requires password and removes all user data`() {
        val email = "withdrawal@gsm.hs.kr"
        mockMvc.post("/api/signup/email-verifications") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email"}"""
        }.andExpect { status { isAccepted() } }
        val verificationCode = verificationRepository.findById(email).orElseThrow().code

        val signupResult = mockMvc.post("/api/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "email":"$email", "username":"withdrawal-user", "password":"password1",
              "passwordConfirm":"password1", "verificationCode":"$verificationCode"
            }"""
        }.andExpect { status { isCreated() } }.andReturn()
        val token = objectMapper.readTree(signupResult.response.contentAsString)["accessToken"].asText()
        val authorization = "Bearer $token"
        val userId = userRepository.findByEmail(email)!!.id!!

        val chatResult = mockMvc.post("/api/chats") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"삭제할 대화"}"""
        }.andExpect { status { isCreated() } }.andReturn()
        val chatId = objectMapper.readTree(chatResult.response.contentAsString)["id"].asLong()
        mockMvc.post("/api/chats/$chatId/messages") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"삭제할 메시지"}"""
        }.andExpect { status { isOk() } }
        mockMvc.post("/api/setting/inquiry") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"삭제할 문의"}"""
        }.andExpect { status { isAccepted() } }

        mockMvc.delete("/api/account") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"password":"wrong-password1"}"""
        }.andExpect { status { isUnauthorized() } }
        assert(userRepository.existsById(userId))

        mockMvc.delete("/api/account") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"password":"password1"}"""
        }.andExpect { status { isNoContent() } }

        assertEquals(null, userRepository.findByEmail(email))
        assertEquals(null, conversationRepository.findByIdAndUserId(chatId, userId))
        assertEquals(0, messageRepository.findAllByConversationIdOrderByCreatedAtAscIdAsc(chatId).size)
        assertEquals(0, inquiryRepository.findAll().count { it.user.id == userId })

        mockMvc.get("/api/main") {
            header("Authorization", authorization)
        }.andExpect { status { isUnauthorized() } }
        mockMvc.post("/api/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"withdrawal-user","password":"password1"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `signup login chat and settings flow`() {
        val email = "s20000@gsm.hs.kr"
        mockMvc.post("/api/signup/email-verifications") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email"}"""
        }.andExpect { status { isAccepted() } }
        val verificationCode = verificationRepository.findById(email).orElseThrow().code

        val signupResult = mockMvc.post("/api/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "email":"$email", "username":"tester", "password":"password1",
              "passwordConfirm":"password1", "verificationCode":"$verificationCode",
              "gender":"NONE", "major":"software", "cohort":10
            }"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.accessToken") { exists() }
        }.andReturn()

        val token = objectMapper.readTree(signupResult.response.contentAsString)["accessToken"].asText()
        val authorization = "Bearer $token"

        val loginResult = mockMvc.post("/api/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"tester","password":"password1"}"""
        }.andExpect { status { isOk() } }.andReturn()
        assert(objectMapper.readTree(loginResult.response.contentAsString)["accessToken"].asText().isNotBlank())

        val chatResult = mockMvc.post("/api/chats") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"Kotlin 질문"}"""
        }.andExpect { status { isCreated() } }.andReturn()
        val chatId = objectMapper.readTree(chatResult.response.contentAsString)["id"].asLong()

        mockMvc.post("/api/chats/$chatId/messages") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"서버를 어떻게 실행해?"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.messages[0].role") { value("USER") }
        }

        mockMvc.patch("/api/general") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"theme":"DARK"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.theme") { value("DARK") }
        }

        mockMvc.get("/api/main") {
            header("Authorization", authorization)
        }.andExpect {
            status { isOk() }
            jsonPath("$.chatList[0].title") { value("Kotlin 질문") }
        }

        mockMvc.get("/api/main?searchKeyword=실행") {
            header("Authorization", authorization)
        }.andExpect {
            status { isOk() }
            jsonPath("$.chatList[0].id") { value(chatId) }
        }

        mockMvc.post("/api/setting/inquiry") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"급식 기능도 추가해주세요"}"""
        }.andExpect { status { isAccepted() } }
        assertEquals("급식 기능도 추가해주세요", inquiryRepository.findAll().single().content)
    }

    private fun signupAndAuthorization(email: String, username: String): String {
        mockMvc.post("/api/signup/email-verifications") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email"}"""
        }.andExpect { status { isAccepted() } }
        val verificationCode = verificationRepository.findById(email).orElseThrow().code
        val signupResult = mockMvc.post("/api/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "email":"$email", "username":"$username", "password":"password1",
              "passwordConfirm":"password1", "verificationCode":"$verificationCode"
            }"""
        }.andExpect { status { isCreated() } }.andReturn()
        val token = objectMapper.readTree(signupResult.response.contentAsString)["accessToken"].asText()
        return "Bearer $token"
    }
}
