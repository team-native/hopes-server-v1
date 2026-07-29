package kr.hs.gsm.hopes

import com.fasterxml.jackson.databind.ObjectMapper
import kr.hs.gsm.hopes.ai.EmbeddingModel
import kr.hs.gsm.hopes.ai.FakeEmbeddingModel
import kr.hs.gsm.hopes.ai.FakeGeminiClient
import kr.hs.gsm.hopes.ai.RagIndexService
import kr.hs.gsm.hopes.domain.EmailVerificationRepository
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant

/** AI 기능(RAG 검색, 프롬프트 구성, Gemini 오류 처리, 답변 저장)을 가짜 Gemini로 검증한다. */
@SpringBootTest(
    properties = [
        "hopes.mail.enabled=false",
        "hopes.ai.enabled=true",
        "hopes.ai.chunks-path=classpath:data/test_rag_chunks.jsonl",
        "hopes.ai.cache-path=./target/ai-test/embeddings_cache.json",
        "hopes.rate-limit.messages-per-minute=3",
        "spring.datasource.url=jdbc:h2:mem:hopes-ai-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    ]
)
@AutoConfigureMockMvc
class HopesAiIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val verificationRepository: EmailVerificationRepository,
    private val gemini: FakeGeminiClient,
    private val rag: RagIndexService,
) {
    @TestConfiguration
    class FakeAiConfig {
        @Bean
        @Primary
        fun fakeGeminiClient(): FakeGeminiClient = FakeGeminiClient()

        @Bean
        @Primary
        fun fakeEmbeddingModel(): EmbeddingModel = FakeEmbeddingModel()
    }

    @BeforeEach
    fun setUp() {
        // 인덱스는 시작 시 백그라운드 스레드에서 구축되므로 준비될 때까지 기다린다.
        val deadline = System.currentTimeMillis() + 10_000
        while (!rag.ready && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(rag.ready, "RAG 인덱스가 준비되지 않았습니다")
        gemini.generateError = null
        gemini.systemPrompts.clear()
        gemini.generatedTurns.clear()
    }

    @Test
    fun `RAG 검색 결과와 사용자 정보로 프롬프트를 구성하고 AI 답변을 저장한다`() {
        val authorization = signupAndToken("ai-user1@gsm.hs.kr", "aiuser1")
        mockMvc.patch("/api/setting") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"customPrompt":"항상 반말로 답해줘"}"""
        }.andExpect { status { isOk() } }
        val chatId = createChat(authorization, "기숙사 질문")

        gemini.answer = "통금은 밤 11시야!"
        mockMvc.post("/api/chats/$chatId/messages") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"기숙사 통금 알려줘"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.messages[0].role") { value("USER") }
            jsonPath("$.messages[1].role") { value("ASSISTANT") }
            jsonPath("$.messages[1].content") { value("통금은 밤 11시야!") }
        }

        val prompt = gemini.systemPrompts.last()
        assertTrue(prompt.contains("기숙사 통금은 밤 11시입니다."), "검색된 청크가 프롬프트에 없습니다")
        assertTrue(prompt.contains("[최종 우선순위 규칙]"), "시스템 안전 규칙이 없습니다")
        assertTrue(!prompt.contains("aiuser1") && !prompt.contains("항상 반말로 답해줘"), "사용자 입력이 시스템 프롬프트에 섞였습니다")
        val userTurn = gemini.generatedTurns.last().last().second
        assertTrue(userTurn.contains("aiuser1"), "질문자 정보가 사용자 턴에 없습니다")
        assertTrue(userTurn.contains("항상 반말로 답해줘"), "사용자 설정이 사용자 턴에 없습니다")
    }

    @Test
    fun `관련 청크가 없으면 사실 창작 금지 안내가 프롬프트에 들어간다`() {
        val authorization = signupAndToken("ai-user2@gsm.hs.kr", "aiuser2")
        val chatId = createChat(authorization, "잡담")
        mockMvc.post("/api/chats/$chatId/messages") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"안녕! 오늘 기분 어때?"}"""
        }.andExpect { status { isOk() } }
        assertTrue(gemini.systemPrompts.last().contains("참고할 응답 없음"))
    }

    @Test
    fun `Gemini 오류 시 502를 반환하고 사용자 메시지도 저장하지 않는다`() {
        val authorization = signupAndToken("ai-user3@gsm.hs.kr", "aiuser3")
        val chatId = createChat(authorization, "오류 테스트")

        gemini.generateError = IllegalStateException("Gemini 장애 (테스트)")
        mockMvc.post("/api/chats/$chatId/messages") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"기숙사 통금 알려줘"}"""
        }.andExpect { status { isBadGateway() } }

        // 실패한 질문은 저장되지 않아야 같은 내용으로 그대로 재시도할 수 있다.
        mockMvc.get("/api/chats/$chatId") {
            header("Authorization", authorization)
        }.andExpect {
            status { isOk() }
            jsonPath("$.messages.length()") { value(0) }
        }

        gemini.generateError = null
        gemini.answer = "재시도 성공 답변"
        mockMvc.post("/api/chats/$chatId/messages") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"기숙사 통금 알려줘"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.messages[1].content") { value("재시도 성공 답변") }
        }
    }

    @Test
    fun `12000자를 넘는 질문은 Gemini 호출 없이 400으로 거절한다`() {
        val authorization = signupAndToken("ai-user5@gsm.hs.kr", "aiuser5")
        val chatId = createChat(authorization, "길이 제한")

        mockMvc.post("/api/chats/$chatId/messages") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"${"a".repeat(12001)}"}"""
        }.andExpect { status { isBadRequest() } }

        assertTrue(gemini.systemPrompts.isEmpty(), "검증에서 걸러진 질문이 Gemini까지 호출됐습니다")
        mockMvc.get("/api/chats/$chatId") {
            header("Authorization", authorization)
        }.andExpect {
            status { isOk() }
            jsonPath("$.messages.length()") { value(0) }
        }
    }

    @Test
    fun `분당 요청 제한을 넘으면 429를 반환한다`() {
        val authorization = signupAndToken("ai-user6@gsm.hs.kr", "aiuser6")
        val chatId = createChat(authorization, "속도 제한")

        // 분 경계에 걸려 카운터가 리셋되면 결과가 흔들리므로 다음 분까지 기다린다.
        val second = Instant.now().epochSecond % 60
        if (second >= 55) Thread.sleep((61 - second) * 1000)

        repeat(3) {
            mockMvc.post("/api/chats/$chatId/messages") {
                header("Authorization", authorization)
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"안녕 $it"}"""
            }.andExpect { status { isOk() } }
        }
        mockMvc.post("/api/chats/$chatId/messages") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"안녕 4"}"""
        }.andExpect { status { isTooManyRequests() } }
    }

    private fun signupAndToken(email: String, username: String): String {
        mockMvc.post("/api/signup/email-verifications") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email"}"""
        }.andExpect { status { isAccepted() } }
        val code = verificationRepository.findById(email).orElseThrow().code
        val result = mockMvc.post("/api/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
              "email":"$email", "username":"$username", "password":"password1",
              "passwordConfirm":"password1", "verificationCode":"$code",
              "gender":"NONE", "major":"software", "cohort":10
            }"""
        }.andExpect { status { isCreated() } }.andReturn()
        return "Bearer " + objectMapper.readTree(result.response.contentAsString)["accessToken"].asText()
    }

    private fun createChat(authorization: String, title: String): Long {
        val result = mockMvc.post("/api/chats") {
            header("Authorization", authorization)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"$title"}"""
        }.andExpect { status { isCreated() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)["id"].asLong()
    }
}
