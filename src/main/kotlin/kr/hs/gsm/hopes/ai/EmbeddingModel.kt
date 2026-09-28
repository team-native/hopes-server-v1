package kr.hs.gsm.hopes.ai

/**
 * 텍스트 임베딩 추상화. 정규화된 벡터를 반환하므로 벡터 간 내적 = 코사인 유사도.
 * taskType: RETRIEVAL_DOCUMENT(청크 색인) / RETRIEVAL_QUERY(질문 검색).
 */
interface EmbeddingModel {
    fun embed(texts: List<String>, taskType: String): List<DoubleArray>
}
