package kr.hs.gsm.hopes.ai

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiDatasetPrivacyTest {
    @Test
    fun `AI 학습 데이터에는 역할과 함께 사람 실명을 포함하지 않는다`() {
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("data/gsm_guide_rag_chunks.jsonl"))
        val nameWithTeacherRole = Regex(
            """(?<![가-힣])(?:[김이박최정강조윤장임한오서신권황안송전홍유고문양손배백허노남심하곽성차주우구민진지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국어은편용][가-힣]{1,2}|남궁[가-힣]{1,2}|황보[가-힣]{1,2})\s*선생님""",
        )
        val personLookupQuestion = Regex("""(?:성함|가장\s+[^\s]+\s+사람은\s+누구)""")
        val violations = resource.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapIndexedNotNull { index, line ->
                (index + 1).takeIf {
                    nameWithTeacherRole.containsMatchIn(line) || personLookupQuestion.containsMatchIn(line)
                }
            }.toList()
        }

        assertTrue(violations.isEmpty(), "사람 실명으로 추정되는 표현이 포함된 줄: ${violations.joinToString()}")
    }
}
