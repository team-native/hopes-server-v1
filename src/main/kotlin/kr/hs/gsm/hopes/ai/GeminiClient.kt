package kr.hs.gsm.hopes.ai

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

/** Google Generative Language REST API 호출 담당 (답변 생성). 임베딩은 [EmbeddingModel]이 로컬에서 처리. */
@Service
class GeminiClient(
    @Value("\${hopes.ai.gemini-api-key}") private val apiKey: String,
    @Value("\${hopes.ai.chat-model}") private val chatModel: String,
    // 답변은 저장 시 12000자로 잘리므로 그보다 길게 생성하면 비용·대기시간만 낭비된다. 생성 단계에서 상한.
    @Value("\${hopes.ai.max-output-tokens:2048}") private val maxOutputTokens: Int = 2048,
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()

    private val rest: RestClient = RestClient.builder()
        .baseUrl("https://generativelanguage.googleapis.com/v1beta")
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(5_000)
            setReadTimeout(60_000)
        })
        .defaultHeader("x-goog-api-key", apiKey)
        .build()

    /** 시스템 프롬프트 + 대화 턴(role: "user"|"model", text)으로 답변 텍스트를 생성한다. */
    fun generate(systemPrompt: String, turns: List<Pair<String, String>>): String =
        generateText(systemPrompt, turns, temperature = 0.1, topP = 0.8, topK = 20, outputTokens = maxOutputTokens)

    /** 최근 대화와 현재 질문을 보고 나이스 급식 조회가 필요한지 AI가 판별한다. */
    fun classifyMealLookup(turns: List<Pair<String, String>>): Boolean {
        val result = generateText(
            MEAL_INTENT_SYSTEM_PROMPT,
            turns,
            temperature = 0.0,
            topP = 1.0,
            topK = 1,
            outputTokens = 8,
        ).trim().trim('`').trim().uppercase()
        return when (result) {
            "MEAL" -> true
            "OTHER" -> false
            else -> throw IllegalStateException("Gemini 급식 의도 판별 응답이 올바르지 않습니다")
        }
    }

    private fun generateText(
        systemPrompt: String,
        turns: List<Pair<String, String>>,
        temperature: Double,
        topP: Double,
        topK: Int,
        outputTokens: Int,
    ): String {
        val body = mapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to systemPrompt))),
            "contents" to turns.map { (role, text) ->
                mapOf("role" to role, "parts" to listOf(mapOf("text" to text)))
            },
            // 낮은 무작위성으로 그럴듯한 세부사항을 덧붙이는 경향을 줄인다.
            "generationConfig" to mapOf(
                "temperature" to temperature,
                "topP" to topP,
                "topK" to topK,
                "maxOutputTokens" to outputTokens,
            ),
        )
        val response = rest.post()
            .uri("/models/{model}:generateContent", chatModel)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(JsonNode::class.java)
            ?: throw IllegalStateException("Gemini 응답이 비어 있습니다")
        val parts = response["candidates"]?.get(0)?.get("content")?.get("parts")
            ?: throw IllegalStateException("Gemini 응답에 후보가 없습니다: ${response.toString().take(300)}")
        val text = parts.mapNotNull { it["text"]?.asText() }.joinToString("")
        if (text.isBlank()) throw IllegalStateException("Gemini가 빈 답변을 반환했습니다")
        return text
    }

    companion object {
        private val MEAL_INTENT_SYSTEM_PROMPT = """
            당신은 학교 챗봇의 요청 분류기입니다. 최근 대화의 맥락과 마지막 사용자 질문을 보고 아래 둘 중 하나만 출력하세요.
            - MEAL: 사용자가 특정 날짜나 상대 날짜의 학교 급식 메뉴를 실제로 조회하려는 요청. 조식·중식·석식, 아침·점심·저녁 메뉴 조회와 급식 대화의 후속 날짜 질문도 포함합니다.
            - OTHER: 급식의 맛·평가·추억·의견, 급식실 위치, 급식 시간·운영 방식, 영양 일반론처럼 나이스 식단 조회가 답이 아닌 질문.
            설명, 문장부호, 마크다운 없이 MEAL 또는 OTHER만 출력하세요.
        """.trimIndent()
    }
}
