package kr.hs.gsm.hopes.ai

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

class NeisMealServiceTest {
    private val today = LocalDate.of(2026, 9, 13)

    @Test
    fun `서울 현재 날짜를 기준으로 상대 날짜와 생략된 연월을 해석한다`() {
        val cases = mapOf(
            "오늘 급식" to LocalDate.of(2026, 9, 13),
            "내일 급식 알려줘" to LocalDate.of(2026, 9, 14),
            "모레 식단" to LocalDate.of(2026, 9, 15),
            "글피 급식" to LocalDate.of(2026, 9, 16),
            "다음주 월요일 급식" to LocalDate.of(2026, 9, 14),
            "이번 주 월요일 급식" to LocalDate.of(2026, 9, 7),
            "지난주 금요일 급식" to LocalDate.of(2026, 9, 4),
            "수요일 급식" to LocalDate.of(2026, 9, 16),
            "15일 급식" to LocalDate.of(2026, 9, 15),
            "10월 2일 급식" to LocalDate.of(2026, 10, 2),
            "2027년 1월 3일 급식" to LocalDate.of(2027, 1, 3),
            "2027-02-04 급식" to LocalDate.of(2027, 2, 4),
        )

        cases.forEach { (question, expected) ->
            assertEquals(expected, MealDateParser.resolve(question, today), question)
        }
    }

    @Test
    fun `조식 중식 석식과 아침 점심 저녁을 말하면 해당 식사만 출력한다`() {
        val provider = MealProvider { date ->
            listOf(
                SchoolMeal(date, "조식", listOf("아침밥"), "500 Kcal"),
                SchoolMeal(date, "중식", listOf("점심밥", "국"), "700 Kcal"),
                SchoolMeal(date, "석식", listOf("저녁밥"), "800 Kcal"),
            )
        }
        val service = NeisMealService(provider, SeoulDateProvider { today })

        mapOf(
            "조식" to "조식",
            "아침" to "조식",
            "중식" to "중식",
            "점심" to "중식",
            "석식" to "석식",
            "저녁" to "석식",
        ).forEach { (word, expected) ->
            val answer = service.replyIfMealQuestion("2026년 4월 1일 $word 급식 알려줘")!!
            assertTrue(answer.startsWith("2026년 4월 1일 급식이야."), word)
            assertTrue(answer.contains("[$expected]"), word)
            assertEquals(1, Regex("\\[(?:조식|중식|석식)]").findAll(answer).count(), word)
        }
    }

    @Test
    fun `식사명을 생략하면 조식 중식 석식을 모두 출력한다`() {
        val service = NeisMealService(
            MealProvider { date ->
                listOf(
                    SchoolMeal(date, "조식", listOf("아침밥"), null),
                    SchoolMeal(date, "중식", listOf("점심밥"), null),
                    SchoolMeal(date, "석식", listOf("저녁밥"), null),
                )
            },
            SeoulDateProvider { today },
        )

        val answer = service.replyIfMealQuestion("2026년 4월 1일 급식 알려줘")!!

        assertTrue(answer.contains("[조식]"))
        assertTrue(answer.contains("[중식]"))
        assertTrue(answer.contains("[석식]"))
    }

    @Test
    fun `등록된 급식이 없으면 지정 문구만 출력한다`() {
        val service = NeisMealService(MealProvider { emptyList() }, SeoulDateProvider { today })

        assertEquals(NeisMealService.NO_MEAL_MESSAGE, service.replyIfMealQuestion("내일 급식"))
    }

    @Test
    fun `잘못된 날짜는 나이스를 호출하지 않고 안내한다`() {
        var called = false
        val service = NeisMealService(
            MealProvider { called = true; emptyList() },
            SeoulDateProvider { today },
        )

        assertEquals("날짜를 확인해줘.", service.replyIfMealQuestion("2월 30일 급식"))
        assertFalse(called)
    }

    @Test
    fun `나이스 성공 응답에서 식단을 읽는다`() {
        val root = ObjectMapper().readTree(
            """{
              "mealServiceDietInfo": [
                {"head":[{"list_total_count":1},{"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다."}}]},
                {"row":[{
                  "MLSV_YMD":"20260401",
                  "MMEAL_SC_NM":"중식",
                  "DDISH_NM":"현미밥<br/>된장국&amp;두부",
                  "CAL_INFO":"700 Kcal"
                }]}
              ]
            }"""
        )

        val meals = NeisMealClient.parseResponse(root, LocalDate.of(2026, 4, 1))

        assertEquals(1, meals.size)
        assertEquals("중식", meals.single().name)
        assertEquals(listOf("현미밥", "된장국&두부"), meals.single().dishes)
    }

    @Test
    fun `나이스 미등록 응답은 빈 식단으로 변환한다`() {
        val root = ObjectMapper().readTree(
            """{"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}"""
        )

        assertTrue(NeisMealClient.parseResponse(root, today).isEmpty())
    }

    @Test
    fun `나이스 오류 응답을 미등록 식단으로 오인하지 않는다`() {
        val root = ObjectMapper().readTree(
            """{"RESULT":{"CODE":"ERROR-290","MESSAGE":"인증키가 유효하지 않습니다."}}"""
        )

        assertThrows(IllegalStateException::class.java) {
            NeisMealClient.parseResponse(root, today)
        }
    }
}
