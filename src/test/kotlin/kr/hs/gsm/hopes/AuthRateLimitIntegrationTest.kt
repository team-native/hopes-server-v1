package kr.hs.gsm.hopes

import kr.hs.gsm.hopes.domain.User
import kr.hs.gsm.hopes.domain.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest(
    properties = [
        "hopes.mail.enabled=false",
        "hopes.ai.enabled=false",
        "hopes.rate-limit.messages-per-minute=0",
        "hopes.rate-limit.login-attempts-per-minute=2",
        "hopes.rate-limit.verification-requests-per-minute=2",
        "hopes.rate-limit.verification-attempts-per-minute=2",
        "hopes.rate-limit.auth-requests-per-ip-per-minute=100",
        "spring.datasource.url=jdbc:h2:mem:hopes-auth-rate-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    ]
)
@AutoConfigureMockMvc
class AuthRateLimitIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val users: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    @Test
    fun `회원가입 인증메일을 너무 자주 요청하면 429를 반환한다`() {
        val body = """{"email":"rate-signup@gsm.hs.kr"}"""
        repeat(2) {
            mockMvc.post("/api/signup/email-verifications") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isAccepted() } }
        }
        mockMvc.post("/api/signup/email-verifications") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isTooManyRequests() } }
    }

    @Test
    fun `비밀번호 재설정 메일도 같은 발송 제한을 적용한다`() {
        val body = """{"email":"rate-password@gsm.hs.kr"}"""
        repeat(2) {
            mockMvc.post("/api/password/request") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isAccepted() } }
        }
        mockMvc.post("/api/password/request") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isTooManyRequests() } }
    }

    @Test
    fun `인증번호 확인을 반복해서 실패하면 429를 반환한다`() {
        val email = "rate-code@gsm.hs.kr"
        mockMvc.post("/api/signup/email-verifications") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email"}"""
        }.andExpect { status { isAccepted() } }

        repeat(2) {
            mockMvc.post("/api/signup/email-verifications/confirm") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$email","code":"999999"}"""
            }.andExpect { status { isBadRequest() } }
        }
        mockMvc.post("/api/signup/email-verifications/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","code":"999999"}"""
        }.andExpect { status { isTooManyRequests() } }
    }

    @Test
    fun `로그인 실패를 반복하면 계정을 분당 제한한다`() {
        users.save(
            User(
                email = "rate-login@gsm.hs.kr",
                username = "rate-login-user",
                nickname = "rate-login-user",
                passwordHash = passwordEncoder.encode("password1"),
            )
        )

        repeat(2) {
            mockMvc.post("/api/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"username":"rate-login-user","password":"wrong-password"}"""
            }.andExpect { status { isUnauthorized() } }
        }
        mockMvc.post("/api/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"rate-login-user","password":"wrong-password"}"""
        }.andExpect { status { isTooManyRequests() } }
    }

    @Test
    fun `이메일과 사용자명을 번갈아도 같은 계정 제한을 우회할 수 없다`() {
        users.save(
            User(
                email = "rate-alias@gsm.hs.kr",
                username = "rate-alias-user",
                nickname = "rate-alias-user",
                passwordHash = passwordEncoder.encode("password1"),
            )
        )

        listOf("rate-alias-user", "rate-alias@gsm.hs.kr").forEach { identifier ->
            mockMvc.post("/api/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"username":"$identifier","password":"wrong-password"}"""
            }.andExpect { status { isUnauthorized() } }
        }
        mockMvc.post("/api/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"rate-alias@gsm.hs.kr","password":"wrong-password"}"""
        }.andExpect { status { isTooManyRequests() } }
    }
}
