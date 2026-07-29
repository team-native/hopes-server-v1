package kr.hs.gsm.hopes

import kr.hs.gsm.hopes.ai.E5EmbeddingModel
import kr.hs.gsm.hopes.ai.EmbeddingModel
import kr.hs.gsm.hopes.ai.GeminiClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 실제 Gemini 키로 고정된 비민감 문구의 임베딩과 답변 생성을 검증하는 수동 통합 테스트.
 * 평소 CI에서는 건너뛰고 LIVE_GEMINI_TEST=true일 때만 외부 API를 호출한다.
 */
@EnabledIfEnvironmentVariable(named = "LIVE_GEMINI_TEST", matches = "true")
@SpringBootTest(
    properties = [
        "hopes.ai.enabled=false",
        "hopes.mail.enabled=false",
        "hopes.rate-limit.messages-per-minute=0",
        "spring.datasource.url=jdbc:h2:mem:hopes-live-gemini;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    ]
)
class LiveGeminiIntegrationTest @Autowired constructor(
    private val client: GeminiClient,
    private val embeddingModel: EmbeddingModel,
) {
    @Test
    fun `실제 로컬 임베딩과 Gemini 답변을 생성한다`() {
        assertTrue(client.hasKey, "GEMINI_API_KEY가 설정되지 않았습니다")
        val vector = embeddingModel.embed(listOf("connection test"), "RETRIEVAL_QUERY").single()
        assertEquals(E5EmbeddingModel.EMBED_DIM, vector.size)

        val answer = client.generate(
            "Return a short acknowledgement without adding facts.",
            listOf("user" to "connection test"),
        )
        assertTrue(answer.isNotBlank(), "Gemini가 빈 답변을 반환했습니다")
    }
}
