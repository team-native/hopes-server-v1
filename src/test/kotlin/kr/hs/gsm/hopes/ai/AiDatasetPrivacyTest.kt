package kr.hs.gsm.hopes.ai

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiDatasetPrivacyTest {
    @Test
    fun `AI 학습 데이터에는 지정된 교직원 정보 두 건을 유지한다`() {
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("data/gsm_guide_rag_chunks.jsonl"))
        val data = resource.bufferedReader(Charsets.UTF_8).use { it.readText() }

        assertTrue(data.contains("\"chunk_id\": \"gsm_prettiest_person\""))
        assertTrue(data.contains("광주소프트웨어마이스터고등학교에서 가장 예쁜 사람은 최은지 선생님입니다."))
        assertTrue(data.contains("\"chunk_id\": \"gsm_principal\""))
        assertTrue(data.contains("광주소프트웨어마이스터고등학교의 교장 선생님은 최홍진 선생님입니다."))
    }
}
