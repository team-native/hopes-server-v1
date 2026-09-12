package kr.hs.gsm.hopes.ai

import com.fasterxml.jackson.databind.JsonNode
import kr.hs.gsm.hopes.api.ApiException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class SchoolMeal(
    val date: LocalDate,
    val name: String,
    val dishes: List<String>,
    val calories: String?,
)

fun interface MealProvider {
    fun find(date: LocalDate): List<SchoolMeal>
}

fun interface SeoulDateProvider {
    fun today(): LocalDate
}

@Component
internal class SystemSeoulDateProvider : SeoulDateProvider {
    override fun today(): LocalDate = LocalDate.now(SEOUL_ZONE)
}

@Component
internal class NeisMealClient(
    restClientBuilder: RestClient.Builder,
    @Value("\${hopes.neis.base-url:https://open.neis.go.kr}") baseUrl: String,
    @Value("\${hopes.neis.api-key:}") private val apiKey: String,
    @Value("\${hopes.neis.education-office-code:F10}") private val educationOfficeCode: String,
    @Value("\${hopes.neis.school-code:7380292}") private val schoolCode: String,
    @Value("\${hopes.neis.connect-timeout-ms:3000}") connectTimeoutMs: Long,
    @Value("\${hopes.neis.read-timeout-ms:5000}") readTimeoutMs: Long,
) : MealProvider {
    private val requestFactory = SimpleClientHttpRequestFactory().apply {
        setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
        setReadTimeout(Duration.ofMillis(readTimeoutMs))
    }
    private val rest = restClientBuilder
        .baseUrl(baseUrl)
        .requestFactory(requestFactory)
        .build()

    override fun find(date: LocalDate): List<SchoolMeal> {
        val response = rest.get()
            .uri { builder ->
                builder.path("/hub/mealServiceDietInfo")
                    .queryParam("Type", "json")
                    .queryParam("pIndex", 1)
                    .queryParam("pSize", 100)
                    .queryParam("ATPT_OFCDC_SC_CODE", educationOfficeCode)
                    .queryParam("SD_SCHUL_CODE", schoolCode)
                    .queryParam("MLSV_YMD", date.format(NEIS_DATE_FORMAT))
                if (apiKey.isNotBlank()) builder.queryParam("KEY", apiKey)
                builder.build()
            }
            .retrieve()
            .body(JsonNode::class.java)
            ?: throw IllegalStateException("나이스 급식 API 응답이 비어 있습니다")

        return parseResponse(response, date)
    }

    companion object {
        internal fun parseResponse(root: JsonNode, requestedDate: LocalDate): List<SchoolMeal> {
            val topLevelResult = root.path("RESULT")
            val topLevelCode = topLevelResult.path("CODE").asText()
            if (topLevelCode == "INFO-200") return emptyList()
            if (topLevelCode.isNotBlank() && topLevelCode != "INFO-000") {
                throw IllegalStateException("나이스 급식 API 오류: $topLevelCode")
            }

            val container = root.path("mealServiceDietInfo")
            val resultCode = container.path(0).path("head").path(1).path("RESULT").path("CODE").asText()
            if (resultCode.isNotBlank() && resultCode != "INFO-000") {
                throw IllegalStateException("나이스 급식 API 오류: $resultCode")
            }

            val rows = container.path(1).path("row")
            if (!rows.isArray) return emptyList()
            return rows.map { row ->
                SchoolMeal(
                    date = row.path("MLSV_YMD").asText().toNeisDateOr(requestedDate),
                    name = row.path("MMEAL_SC_NM").asText(),
                    dishes = row.path("DDISH_NM").asText().toDishList(),
                    calories = row.path("CAL_INFO").asText().takeIf(String::isNotBlank),
                )
            }
        }
    }
}

@Service
class NeisMealService(
    private val provider: MealProvider,
    private val dateProvider: SeoulDateProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun isMealQuestion(question: String): Boolean {
        val normalized = question.replace(" ", "")
        return MEAL_WORD.containsMatchIn(question) ||
            MEAL_CONTEXT.containsMatchIn(normalized)
    }

    fun replyIfMealQuestion(question: String): String? {
        if (!isMealQuestion(question)) return null
        val date = try {
            MealDateParser.resolve(question, dateProvider.today())
        } catch (_: IllegalArgumentException) {
            return "날짜를 확인해줘."
        }

        val requestedMealNames = requestedMealNames(question)
        val meals = try {
            provider.find(date)
        } catch (e: Exception) {
            log.error("[neis] {} 급식 조회 실패", date, e)
            throw ApiException(HttpStatus.BAD_GATEWAY, "급식 정보를 불러오지 못했습니다. 잠시 후 다시 시도해주세요")
        }.filter { requestedMealNames.isEmpty() || it.name in requestedMealNames }

        if (meals.isEmpty()) return NO_MEAL_MESSAGE
        return buildString {
            append(date.format(KOREAN_DATE_FORMAT)).append(" 급식이야.")
            meals.forEach { meal ->
                append("\n\n[").append(meal.name).append("]\n")
                append(meal.dishes.joinToString("\n") { "- $it" })
                meal.calories?.let { append("\n- 칼로리: ").append(it) }
            }
        }
    }

    private fun requestedMealNames(question: String): Set<String> = buildSet {
        if (Regex("조식|아침(?:밥|급식|메뉴)?").containsMatchIn(question)) add("조식")
        if (Regex("중식|점심(?:밥|급식|메뉴)?").containsMatchIn(question)) add("중식")
        if (Regex("석식|저녁(?:밥|급식|메뉴)?").containsMatchIn(question)) add("석식")
    }

    companion object {
        const val NO_MEAL_MESSAGE = "급식이 등록되지 않았습니다."
        private val MEAL_WORD = Regex("급식|식단")
        private val MEAL_CONTEXT = Regex("(?:조식|중식|석식|아침|점심|저녁|밥).*(?:메뉴|뭐(?:야|지|임|나와|먹)|알려)")
        private val KOREAN_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy년 M월 d일")
    }
}

internal object MealDateParser {
    private val koreanFullDate = Regex("(?<!\\d)(\\d{4})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일")
    private val numericFullDate = Regex("(?<!\\d)(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})(?!\\d)")
    private val monthAndDay = Regex("(?<!\\d)(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일")
    private val numericMonthAndDay = Regex("(?<![\\d년])(\\d{1,2})[./-](\\d{1,2})(?!\\d)")
    private val dayOnly = Regex("(?<!\\d)(\\d{1,2})\\s*일")
    private val weekday = Regex("(?:(지난|이번|다음)\\s*주\\s*)?(월|화|수|목|금|토|일)요일")

    fun resolve(question: String, today: LocalDate): LocalDate {
        RELATIVE_DAYS.firstOrNull { (word, _) -> question.contains(word) }
            ?.let { (_, offset) -> return today.plusDays(offset) }

        weekday.find(question)?.destructured?.let { (week, day) ->
            val targetDay = WEEKDAYS.getValue(day)
            if (week.isBlank()) return today.with(TemporalAdjusters.nextOrSame(targetDay))

            val weekOffset = when (week) {
                "지난" -> -1L
                "이번" -> 0L
                "다음" -> 1L
                else -> 0L
            }
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return monday.plusWeeks(weekOffset).plusDays((targetDay.value - DayOfWeek.MONDAY.value).toLong())
        }

        koreanFullDate.find(question)?.destructured?.let { (year, month, day) ->
            return checkedDate(year.toInt(), month.toInt(), day.toInt())
        }
        numericFullDate.find(question)?.destructured?.let { (year, month, day) ->
            return checkedDate(year.toInt(), month.toInt(), day.toInt())
        }
        monthAndDay.find(question)?.destructured?.let { (month, day) ->
            return checkedDate(today.year, month.toInt(), day.toInt())
        }
        numericMonthAndDay.find(question)?.destructured?.let { (month, day) ->
            return checkedDate(today.year, month.toInt(), day.toInt())
        }
        dayOnly.find(question)?.destructured?.let { (day) ->
            return checkedDate(today.year, today.monthValue, day.toInt())
        }
        return today
    }

    private fun checkedDate(year: Int, month: Int, day: Int): LocalDate = try {
        LocalDate.of(year, month, day)
    } catch (e: RuntimeException) {
        throw IllegalArgumentException("잘못된 날짜입니다", e)
    }

    private val RELATIVE_DAYS = listOf(
        "그제" to -2L,
        "어제" to -1L,
        "오늘" to 0L,
        "내일" to 1L,
        "모레" to 2L,
        "글피" to 3L,
    )

    private val WEEKDAYS = mapOf(
        "월" to DayOfWeek.MONDAY,
        "화" to DayOfWeek.TUESDAY,
        "수" to DayOfWeek.WEDNESDAY,
        "목" to DayOfWeek.THURSDAY,
        "금" to DayOfWeek.FRIDAY,
        "토" to DayOfWeek.SATURDAY,
        "일" to DayOfWeek.SUNDAY,
    )
}

private fun String.toNeisDateOr(fallback: LocalDate): LocalDate =
    runCatching { LocalDate.parse(this, NEIS_DATE_FORMAT) }.getOrDefault(fallback)

private fun String.toDishList(): List<String> =
    replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .lines()
        .map(String::trim)
        .filter(String::isNotEmpty)

private val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
private val NEIS_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
