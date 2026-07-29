package kr.hs.gsm.hopes

import com.fasterxml.jackson.databind.ObjectMapper
import kr.hs.gsm.hopes.domain.EmailVerificationRepository
import kr.hs.gsm.hopes.domain.InquiryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
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
}
