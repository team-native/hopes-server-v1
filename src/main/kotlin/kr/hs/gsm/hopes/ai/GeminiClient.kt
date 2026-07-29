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
    fun generate(systemPrompt: String, turns: List<Pair<String, String>>): String {
        val body = mapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to systemPrompt))),
            "contents" to turns.map { (role, text) ->
                mapOf("role" to role, "parts" to listOf(mapOf("text" to text)))
            },
            // 낮은 temperature → 창작 억제, 검색된 데이터에 근거한 답변 유도.
            "generationConfig" to mapOf("temperature" to 0.4, "topP" to 0.9, "maxOutputTokens" to maxOutputTokens),
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
}
