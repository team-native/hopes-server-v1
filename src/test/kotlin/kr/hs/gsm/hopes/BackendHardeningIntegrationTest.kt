package kr.hs.gsm.hopes

import com.fasterxml.jackson.databind.ObjectMapper
import kr.hs.gsm.hopes.domain.ChatMessage
import kr.hs.gsm.hopes.domain.ChatMessageRepository
import kr.hs.gsm.hopes.domain.Conversation
import kr.hs.gsm.hopes.domain.ConversationRepository
import kr.hs.gsm.hopes.domain.EmailVerificationRepository
import kr.hs.gsm.hopes.domain.MessageRole
import kr.hs.gsm.hopes.domain.UserRepository
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
        "hopes.rate-limit.messages-per-minute=0",
        "hopes.rate-limit.login-attempts-per-minute=0",
        "hopes.rate-limit.verification-requests-per-minute=0",
        "hopes.rate-limit.verification-attempts-per-minute=0",
        "hopes.rate-limit.auth-requests-per-ip-per-minute=0",
        "spring.datasource.url=jdbc:h2:mem:hopes-hardening-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    ]
)
@AutoConfigureMockMvc
class BackendHardeningIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val verifications: EmailVerificationRepository,
    private val users: UserRepository,
    private val conversations: ConversationRepository,
    private val messages: ChatMessageRepository,
) {
    @Test
    fun `로그아웃하면 기존 토큰은 즉시 무효화된다`() {
        val token = signup("logout-user@gsm.hs.kr", "logout-user")

        mockMvc.post("/api/logout") {
            header("Authorization", token)
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/mypage") {
            header("Authorization", token)
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `비밀번호를 바꾸면 기존 토큰과 이전 비밀번호는 사용할 수 없다`() {
        val email = "reset-user@gsm.hs.kr"
        val token = signup(email, "reset-user")

        mockMvc.post("/api/password/request") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email"}"""
        }.andExpect { status { isAccepted() } }
        val code = verifications.findById(email).orElseThrow().code

        mockMvc.post("/api/password/reset") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","code":"$code","newPassword":"newpassword2"}"""
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/mypage") {
            header("Authorization", token)
        }.andExpect { status { isUnauthorized() } }
        login("reset-user", "password1").andExpect { status { isUnauthorized() } }
        login("reset-user", "newpassword2").andExpect { status { isOk() } }
    }

    @Test
    fun `DB 컬럼보다 긴 요청값은 저장 전에 400으로 거절한다`() {
        mockMvc.post("/api/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
                "email":"long-name@gsm.hs.kr",
                "username":"${"a".repeat(51)}",
                "password":"password1",
                "passwordConfirm":"password1",
                "verificationCode":"123456"
            }"""
        }.andExpect { status { isBadRequest() } }

        val token = signup("validation-user@gsm.hs.kr", "validation-user")
        mockMvc.post("/api/chats") {
            header("Authorization", token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"${"a".repeat(256)}"}"""
        }.andExpect { status { isBadRequest() } }
        mockMvc.patch("/api/setting") {
            header("Authorization", token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"customPrompt":"${"a".repeat(4001)}"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `페이지 크기는 최대 100으로 제한한다`() {
        val token = signup("page-user@gsm.hs.kr", "page-user")
        mockMvc.get("/api/main?size=101") {
            header("Authorization", token)
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `대화와 메시지는 페이지 단위로만 반환한다`() {
        val email = "paged-data-user@gsm.hs.kr"
        val token = signup(email, "paged-data-user")
        val user = users.findByEmail(email)!!
        conversations.saveAll((1..101).map { Conversation(user = user, title = "대화 $it") })

        mockMvc.get("/api/main?size=100") {
            header("Authorization", token)
        }.andExpect {
            status { isOk() }
            jsonPath("$.chatList.length()") { value(100) }
            jsonPath("$.hasNext") { value(true) }
        }

        val conversation = conversations.save(Conversation(user = user, title = "메시지 페이지"))
        messages.saveAll(
            (1..101).map {
                ChatMessage(conversation = conversation, role = MessageRole.USER, content = "메시지 $it")
            }
        )
        mockMvc.get("/api/chats/${conversation.id}?messageSize=100") {
            header("Authorization", token)
        }.andExpect {
            status { isOk() }
            jsonPath("$.messages.length()") { value(100) }
            jsonPath("$.hasMoreMessages") { value(true) }
        }
    }

    private fun signup(email: String, username: String): String {
        mockMvc.post("/api/signup/email-verifications") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email"}"""
        }.andExpect { status { isAccepted() } }
        val code = verifications.findById(email).orElseThrow().code
        val result = mockMvc.post("/api/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
                "email":"$email",
                "username":"$username",
                "password":"password1",
                "passwordConfirm":"password1",
                "verificationCode":"$code"
            }"""
        }.andExpect { status { isCreated() } }.andReturn()
        return "Bearer " + objectMapper.readTree(result.response.contentAsString)["accessToken"].asText()
    }

    private fun login(username: String, password: String) = mockMvc.post("/api/login") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"username":"$username","password":"$password"}"""
    }
}
