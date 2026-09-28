package kr.hs.gsm.hopes.ai

/**
 * 실제 로컬 임베딩 대신 쓰는 테스트 대역.
 * 키워드별 직교 기저 벡터를 반환해 코사인 유사도가 0 또는 1로 결정적이고,
 * 임베딩 실패 횟수를 테스트에서 조작할 수 있다.
 */
class FakeEmbeddingModel : EmbeddingModel {
    val embeddedTexts = mutableListOf<String>()
    var embedFailures = 0

    override fun embed(texts: List<String>, taskType: String): List<DoubleArray> {
        if (embedFailures > 0) {
            embedFailures--
            throw IllegalStateException("임베딩 실패 (테스트)")
        }
        embeddedTexts += texts
        return texts.map(::vectorFor)
    }

    private fun vectorFor(text: String): DoubleArray = when {
        "기숙사" in text -> doubleArrayOf(1.0, 0.0, 0.0)
        "급식" in text -> doubleArrayOf(0.0, 1.0, 0.0)
        else -> doubleArrayOf(0.0, 0.0, 1.0)
    }
}
