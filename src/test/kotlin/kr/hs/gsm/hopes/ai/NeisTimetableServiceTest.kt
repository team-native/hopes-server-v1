package kr.hs.gsm.hopes.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate
import kr.hs.gsm.hopes.api.ApiException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NeisTimetableServiceTest {
    private val today = LocalDate.of(2026, 11, 17) // 화요일

    private fun service(
        onFind: (LocalDate, Int, Int) -> List<TimetablePeriod> = { _, _, _ ->
            listOf(TimetablePeriod(1, "네트워크 프로그래밍 구현"), TimetablePeriod(2, "미적분Ⅰ"))
        }
    ) = NeisTimetableService(TimetableProvider { d, g, c -> onFind(d, g, c) }, SeoulDateProvider { today })

    @Test
    fun `parses neis rows in period order and strips the star mark`() {
        val json =
            """{"hisTimetable":[{"head":[{"list_total_count":3},{"RESULT":{"CODE":"INFO-000"}}]},
               {"row":[{"PERIO":"3","ITRT_CNTNT":"미적분Ⅰ"},{"PERIO":"1","ITRT_CNTNT":"* 네트워크 프로그래밍 구현"},
                       {"PERIO":"2","ITRT_CNTNT":"* 네트워크 프로그래밍 구현"}]}]}"""
        val periods = NeisTimetableClient.parseResponse(jacksonObjectMapper().readTree(json))
        assertEquals(listOf(1, 2, 3), periods.map { it.period })
        assertEquals("네트워크 프로그래밍 구현", periods.first().subject)
        val none = NeisTimetableClient.parseResponse(jacksonObjectMapper().readTree("""{"RESULT":{"CODE":"INFO-200"}}"""))
        assertTrue(none.isEmpty())
    }

    @Test
    fun `reads grade class and date from the question`() {
        var asked: Triple<LocalDate, Int, Int>? = null
        val s = service { d, g, c -> asked = Triple(d, g, c); listOf(TimetablePeriod(1, "영어Ⅱ")) }

        val answer = s.replyForTimetableLookup("2학년 1반 시간표 알려줘")
        assertEquals(Triple(today, 2, 1), asked)
        assertTrue(answer.startsWith("11월 17일(화) 2학년 1반 시간표야."), answer)
        assertTrue(answer.contains("- 1교시: 영어Ⅱ"), answer)

        // "2-1"을 2월 1일로 읽지 않는다.
        s.replyForTimetableLookup("2-1 내일 시간표")
        assertEquals(Triple(today.plusDays(1), 2, 1), asked)
        s.replyForTimetableLookup("3학년 4반 목요일 몇 교시 뭐야")
        assertEquals(Triple(LocalDate.of(2026, 11, 19), 3, 4), asked)
    }

    @Test
    fun `uses the class from a previous question and handles missing data`() {
        var asked: Triple<LocalDate, Int, Int>? = null
        val s = service { d, g, c -> asked = Triple(d, g, c); emptyList() }
        val answer = s.replyForTimetableLookup("내일 시간표는?", listOf("1학년 3반 시간표 알려줘"))
        assertEquals(Triple(today.plusDays(1), 1, 3), asked)
        assertTrue(answer.contains("등록되어 있지 않아"), answer)

        assertTrue(service().replyForTimetableLookup("시간표 알려줘").startsWith("몇 학년 몇 반"))
        assertTrue(service().replyForTimetableLookup("2학년 1반 토요일 시간표").contains("주말"))
    }

    @Test
    fun `neis failure becomes a 502 error`() {
        val e = assertThrows(ApiException::class.java) {
            service { _, _, _ -> throw IllegalStateException("down") }.replyForTimetableLookup("2학년 1반 시간표")
        }
        assertEquals(502, e.status.value())
    }
}
