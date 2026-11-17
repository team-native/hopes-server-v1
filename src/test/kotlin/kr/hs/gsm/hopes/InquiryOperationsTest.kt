package kr.hs.gsm.hopes

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import kr.hs.gsm.hopes.domain.*
import kr.hs.gsm.hopes.feature.SUPPORT_EMAIL
import kr.hs.gsm.hopes.security.AccessTokenService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.mail.MailSendException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*

@SpringBootTest(
    properties =
        [
            "hopes.mail.enabled=true",
            "hopes.ai.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:inquiry-ops;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        ]
)
@AutoConfigureMockMvc
class InquiryOperationsTest
@Autowired
constructor(
    val mvc: MockMvc,
    val mapper: ObjectMapper,
    val users: UserRepository,
    val inquiries: InquiryRepository,
    val chats: ConversationRepository,
    val messages: ChatMessageRepository,
    val tokens: AccessTokenService,
) {
    @MockBean lateinit var mail: JavaMailSender

    @BeforeEach
    fun resetMail() {
        reset(mail)
    }

    private fun user(admin: Boolean = false): User {
        val n = UUID.randomUUID().toString()
        return users.saveAndFlush(
            User(
                email = "$n@example.invalid",
                username = n,
                nickname = "QA",
                role = if (admin) UserRole.ADMIN else UserRole.USER,
            )
        )
    }

    private fun auth(u: User) = "Bearer " + tokens.create(u.email, u.tokenVersion)

    private fun call(
        method: String,
        path: String,
        u: User?,
        data: String? = null,
        status: Int = 200,
    ) =
        mvc.perform(
                request(org.springframework.http.HttpMethod.valueOf(method), path).apply {
                    if (u != null) header("Authorization", auth(u))
                    if (data != null) contentType(MediaType.APPLICATION_JSON).content(data)
                }
            )
            .andReturn()
            .response
            .also { assertEquals(status, it.status, it.contentAsString) }

    @Test
    fun `only admin can reply and identical retries never send a second message`() {
        val owner = user()
        val admin = user(true)
        val inquiry =
            inquiries.saveAndFlush(
                Inquiry(user = owner, content = "help", replyEmail = "reply@example.invalid")
            )
        call("GET", "/api/admin/inquiries", null, status = 401)
        call("GET", "/api/admin/inquiries", owner, status = 403)
        val body = """{"content":"answer","clientRequestId":"reply-1"}"""
        val first =
            mapper.readTree(
                call("POST", "/api/admin/inquiries/${inquiry.id}/reply", admin, body)
                    .contentAsString
            )
        assertEquals("SENT", first["replyStatus"].asText())
        call("POST", "/api/admin/inquiries/${inquiry.id}/reply", admin, body)
        call(
            "POST",
            "/api/admin/inquiries/${inquiry.id}/reply",
            admin,
            """{"content":"changed","clientRequestId":"reply-1"}""",
            409,
        )
        val capture = ArgumentCaptor.forClass(SimpleMailMessage::class.java)
        verify(mail, times(1)).send(capture.capture())
        assertEquals("reply@example.invalid", capture.value.to!!.single())
        assertEquals(SUPPORT_EMAIL, capture.value.replyTo)
        val mine = mapper.readTree(call("GET", "/api/setting/inquiries", owner).contentAsString)
        assertTrue(
            mine["items"].any {
                it["id"].asLong() == inquiry.id && it["answer"].asText() == "answer"
            }
        )
        val other = mapper.readTree(call("GET", "/api/setting/inquiries", user()).contentAsString)
        assertEquals(0, other["items"].size())
    }

    @Test
    fun `uncertain SMTP delivery is recorded and retry does not duplicate an email`() {
        val row = inquiries.saveAndFlush(Inquiry(user = user(), content = "help"))
        val admin = user(true)
        doThrow(MailSendException("test failure"))
            .`when`(mail)
            .send(any(SimpleMailMessage::class.java))
        val body = """{"content":"answer","clientRequestId":"failure"}"""
        call("POST", "/api/admin/inquiries/${row.id}/reply", admin, body, 502)
        assertEquals("DELIVERY_UNKNOWN", inquiries.findById(row.id!!).get().replyStatus)
        call("POST", "/api/admin/inquiries/${row.id}/reply", admin, body)
        verify(mail, times(1)).send(any(SimpleMailMessage::class.java))
    }
}
