package kr.hs.gsm.hopes

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

@SpringBootTest(
    properties =
        [
            "hopes.mail.enabled=false",
            "hopes.ai.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:privacy-policy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        ]
)
@AutoConfigureMockMvc
class PrivacyPolicyTest @Autowired constructor(val mvc: MockMvc) {
    @Test
    fun `policy is public and includes support retention and external processing`() {
        val response = mvc.perform(get("/api/privacy/policy")).andReturn().response
        assertEquals(200, response.status)
        val text = response.getContentAsString(Charsets.UTF_8)
        assertTrue(text.contains("team.native.official@gmail.com"))
        assertTrue(text.contains("365"))
        assertTrue(text.contains("Discord"))
        assertTrue(response.contentType!!.startsWith("text/html"))
    }
}
