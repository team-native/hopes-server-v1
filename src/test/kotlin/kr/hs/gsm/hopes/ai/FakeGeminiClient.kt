package kr.hs.gsm.hopes.ai

/**
 * 실제 Gemini API 대신 쓰는 테스트 대역 (답변 생성 전용).
 * 답변·오류를 테스트에서 조작할 수 있다. 임베딩은 [FakeEmbeddingModel]이 담당.
 */
class FakeGeminiClient : GeminiClient("test-key", "test-chat-model") {
    val systemPrompts = mutableListOf<String>()
    val generatedTurns = mutableListOf<List<Pair<String, String>>>()
    var generateError: RuntimeException? = null
    var answer: String = "테스트 답변"

    override val hasKey: Boolean get() = true

    override fun generate(systemPrompt: String, turns: List<Pair<String, String>>): String {
        generateError?.let { throw it }
        systemPrompts += systemPrompt
        generatedTurns += turns
        return answer
    }
}
