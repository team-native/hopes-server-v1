package kr.hs.gsm.hopes.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import kr.hs.gsm.hopes.api.ApiException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient

class OpenAiClientTest {
    private val mapper = jacksonObjectMapper()
    private val builder = RestClient.builder().baseUrl("https://api.openai.com/v1")
    private val server = MockRestServiceServer.bindTo(builder).build()

    // Synthetic model and key; no real credentials or network calls.
    private fun client(key: String = "test-only-key", model: String = "test-vision-model") =
        OpenAiClient(key, model, 640, builder.build(), "test-classifier-model", 256)

    private fun completed(text: String) =
        mapper.writeValueAsString(
            mapOf(
                "status" to "completed",
                "output" to
                    listOf(
                        mapOf("type" to "reasoning", "summary" to emptyList<String>()),
                        mapOf(
                            "type" to "message",
                            "content" to listOf(mapOf("type" to "output_text", "text" to text)),
                        ),
                    ),
            )
        )

    @Test
    fun `serializes text history images PDFs and disables response storage`() {
        val image = byteArrayOf(1, 2, 3)
        val pdf = "%PDF-synthetic".toByteArray()
        val answer = "## 결과\n\n**본문**\n\n```kotlin\nval x = 1\n```"
        server
            .expect(requestTo("https://api.openai.com/v1/responses"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer test-only-key"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.store").value(false))
            .andExpect(jsonPath("$.model").value("test-vision-model"))
            .andExpect(jsonPath("$.instructions").value("system-rules"))
            .andExpect(jsonPath("$.max_output_tokens").value(640))
            .andExpect(jsonPath("$.input[0].role").value("user"))
            .andExpect(jsonPath("$.input[1].role").value("assistant"))
            .andExpect(jsonPath("$.input[1].content").value("이전 답변"))
            .andExpect(jsonPath("$.input[2].content[0].type").value("input_text"))
            .andExpect(jsonPath("$.input[2].content[2].type").value("input_image"))
            .andExpect(
                jsonPath("$.input[2].content[2].image_url").value("data:image/png;base64,AQID")
            )
            .andExpect(jsonPath("$.input[2].content[4].type").value("input_file"))
            .andExpect(jsonPath("$.input[2].content[4].filename").value("attachment-2.pdf"))
            .andExpect(
                jsonPath("$.input[2].content[4].file_data")
                    .value(
                        "data:application/pdf;base64," +
                            java.util.Base64.getEncoder().encodeToString(pdf)
                    )
            )
            .andExpect(jsonPath("$.temperature").doesNotExist())
            .andRespond(withSuccess(completed(answer), MediaType.APPLICATION_JSON))
        assertEquals(
            answer,
            client()
                .generateWithAttachments(
                    "system-rules",
                    listOf("user" to "이전 질문", "model" to "이전 답변", "user" to "첨부 분석"),
                    listOf(AiAttachment("image/png", image), AiAttachment("application/pdf", pdf)),
                ),
        )
        server.verify()
    }

    @Test
    fun `meal classifier uses its model and accepts only the two labels`() {
        server
            .expect(requestTo("https://api.openai.com/v1/responses"))
            .andExpect(jsonPath("$.model").value("test-classifier-model"))
            .andExpect(jsonPath("$.max_output_tokens").value(256))
            .andRespond(withSuccess(completed("MEAL"), MediaType.APPLICATION_JSON))
        assertTrue(client().classifyMealLookup(listOf("user" to "내일 점심 메뉴")))
        server.verify()
    }

    @Test
    fun `invalid classifier output is rejected instead of guessing`() {
        server
            .expect(requestTo("https://api.openai.com/v1/responses"))
            .andRespond(withSuccess(completed("Probably MEAL"), MediaType.APPLICATION_JSON))
        assertEquals(
            HttpStatus.BAD_GATEWAY,
            assertThrows(ApiException::class.java) {
                    client().classifyMealLookup(listOf("user" to "질문"))
                }
                .status,
        )
        server.verify()
    }

    @Test
    fun `missing key or model makes no request`() {
        for (c in listOf(client(key = ""), client(model = ""))) {
            assertFalse(c.hasKey)
            assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ApiException::class.java) {
                        c.generate("rules", listOf("user" to "질문"))
                    }
                    .status,
            )
        }
        server.verify()
    }

    @Test
    fun `incomplete failed refused and empty outputs are not saved as successful answers`() {
        val responses =
            listOf(
                """{"status":"incomplete","output":[{"type":"message","content":[{"type":"output_text","text":"잘린 답변"}]}]}""",
                """{"status":"failed","output":[]}""",
                """{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"details"}]}]}""",
                completed(" "),
            )
        responses.forEach { body ->
            server
                .expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
        }
        responses.forEach { _ ->
            assertEquals(
                HttpStatus.BAD_GATEWAY,
                assertThrows(ApiException::class.java) {
                        client().generate("rules", listOf("user" to "질문"))
                    }
                    .status,
            )
        }
        server.verify()
    }

    @Test
    fun `HTTP failures never expose the upstream body or credentials`() {
        server
            .expect(requestTo("https://api.openai.com/v1/responses"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("private prompt test-only-key"))
        val error =
            assertThrows(ApiException::class.java) {
                client().generate("rules", listOf("user" to "질문"))
            }
        assertEquals(HttpStatus.BAD_GATEWAY, error.status)
        assertFalse(error.message.contains("private"))
        assertFalse(error.message.contains("test-only-key"))
        assertNull(error.cause)
        server.verify()
    }

    @Test
    fun `provider rate limit and transport failure return retryable errors`() {
        server
            .expect(requestTo("https://api.openai.com/v1/responses"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        server
            .expect(requestTo("https://api.openai.com/v1/responses"))
            .andRespond(withException(IOException("sensitive transport details")))
        assertEquals(
            HttpStatus.TOO_MANY_REQUESTS,
            assertThrows(ApiException::class.java) {
                    client().generate("rules", listOf("user" to "질문"))
                }
                .status,
        )
        val transport =
            assertThrows(ApiException::class.java) {
                client().generate("rules", listOf("user" to "질문"))
            }
        assertEquals(HttpStatus.BAD_GATEWAY, transport.status)
        assertFalse(transport.message.contains("sensitive"))
        assertNull(transport.cause)
        server.verify()
    }
}
