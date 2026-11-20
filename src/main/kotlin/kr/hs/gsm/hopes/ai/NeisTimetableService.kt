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
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 하루 시간표의 한 교시. */
data class TimetablePeriod(val period: Int, val subject: String)

fun interface TimetableProvider {
    fun find(date: LocalDate, grade: Int, classNumber: Int): List<TimetablePeriod>
}

/** 나이스 고등학교 시간표 API(hisTimetable). 학교 코드·API 키는 급식과 같은 hopes.neis 설정을 쓴다. */
@Component
internal class NeisTimetableClient(
    restClientBuilder: RestClient.Builder,
    @Value("\${hopes.neis.base-url:https://open.neis.go.kr}") baseUrl: String,
    @Value("\${hopes.neis.api-key:}") private val apiKey: String,
    @Value("\${hopes.neis.education-office-code:F10}") private val educationOfficeCode: String,
    @Value("\${hopes.neis.school-code:7140392}") private val schoolCode: String,
    @Value("\${hopes.neis.connect-timeout-ms:3000}") connectTimeoutMs: Long,
    @Value("\${hopes.neis.read-timeout-ms:5000}") readTimeoutMs: Long,
) : TimetableProvider {
    private val requestFactory = SimpleClientHttpRequestFactory().apply {
        setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
        setReadTimeout(Duration.ofMillis(readTimeoutMs))
    }
    private val rest = restClientBuilder
        .baseUrl(baseUrl)
        .requestFactory(requestFactory)
        .build()

    override fun find(date: LocalDate, grade: Int, classNumber: Int): List<TimetablePeriod> {
        val response = rest.get()
            .uri { builder ->
                builder.path("/hub/hisTimetable")
                    .queryParam("Type", "json")
                    .queryParam("pIndex", 1)
                    .queryParam("pSize", 100)
                    .queryParam("ATPT_OFCDC_SC_CODE", educationOfficeCode)
                    .queryParam("SD_SCHUL_CODE", schoolCode)
                    .queryParam("ALL_TI_YMD", date.format(DateTimeFormatter.BASIC_ISO_DATE))
                    .queryParam("GRADE", grade)
                    .queryParam("CLASS_NM", classNumber)
                if (apiKey.isNotBlank()) builder.queryParam("KEY", apiKey)
                builder.build()
            }
            .retrieve()
            .body(JsonNode::class.java)
            ?: throw IllegalStateException("나이스 시간표 API 응답이 비어 있습니다")
        return parseResponse(response)
    }

    companion object {
        internal fun parseResponse(root: JsonNode): List<TimetablePeriod> {
            // INFO-200: 해당 날짜에 등록된 시간표 없음(주말·방학·미입력)
            val topLevelCode = root.path("RESULT").path("CODE").asText()
            if (topLevelCode == "INFO-200") return emptyList()
            if (topLevelCode.isNotBlank() && topLevelCode != "INFO-000") {
                throw IllegalStateException("나이스 시간표 API 오류: $topLevelCode")
            }
            val container = root.path("hisTimetable")
            val resultCode = container.path(0).path("head").path(1).path("RESULT").path("CODE").asText()
            if (resultCode.isNotBlank() && resultCode != "INFO-000") {
                throw IllegalStateException("나이스 시간표 API 오류: $resultCode")
            }
            val rows = container.path(1).path("row")
            if (!rows.isArray) return emptyList()
            return rows
                .mapNotNull { row ->
                    val period = row.path("PERIO").asText().trim().toIntOrNull() ?: return@mapNotNull null
                    // 나이스 과목명 앞의 "* " 표시는 빼고 보여 준다.
                    val subject = row.path("ITRT_CNTNT").asText().trim().removePrefix("*").trim()
                    if (subject.isEmpty()) null else TimetablePeriod(period, subject)
                }
                .distinctBy { it.period }
                .sortedBy { it.period }
        }
    }
}

@Service
class NeisTimetableService(
    private val provider: TimetableProvider,
    private val dateProvider: SeoulDateProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * "2학년 1반 시간표", "2-1 내일 시간표", "3학년 2반 월요일 몇 교시 뭐야" 같은 질문에 답한다.
     * 이번 질문에 학년·반이 없으면 최근 질문(예: "2학년 1반 시간표 알려줘" 다음의 "내일은?")에서 찾는다.
     */
    fun replyForTimetableLookup(question: String, previousQuestions: List<String> = emptyList()): String {
        val target = classOf(question) ?: previousQuestions.asReversed().firstNotNullOfOrNull(::classOf)
            ?: return "몇 학년 몇 반 시간표인지 알려줘. 예: 2학년 1반 내일 시간표"
        val (grade, classNumber) = target
        // "2-1"이 2월 1일로 읽히지 않도록 학년·반 표현을 지운 뒤 날짜를 해석한다.
        val dateText = question.replace(CLASS_PATTERN, " ")
        val date = try {
            MealDateParser.resolve(dateText, dateProvider.today())
        } catch (_: IllegalArgumentException) {
            return "날짜를 확인해줘."
        }
        val title = "${date.format(KOREAN_DATE_FORMAT)}(${WEEKDAY_NAMES.getValue(date.dayOfWeek)}) ${grade}학년 ${classNumber}반"
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            return "${title}은 주말이라 수업 시간표가 없어."
        }
        val periods = try {
            provider.find(date, grade, classNumber)
        } catch (e: Exception) {
            log.error("[neis] {} {}학년 {}반 시간표 조회 실패", date, grade, classNumber, e)
            throw ApiException(HttpStatus.BAD_GATEWAY, "시간표 정보를 불러오지 못했습니다. 잠시 후 다시 시도해주세요")
        }
        if (periods.isEmpty()) {
            return "$title 시간표가 나이스에 등록되어 있지 않아. 방학·행사일이거나 아직 입력되지 않았을 수 있어."
        }
        return buildString {
            append(title).append(" 시간표야.\n")
            periods.forEach { append("\n- ").append(it.period).append("교시: ").append(it.subject) }
            append("\n\n나이스에 등록된 기준이라 실제 수업 변경은 반영되지 않았을 수 있어.")
        }
    }

    private fun classOf(text: String): Pair<Int, Int>? =
        CLASS_PATTERN.find(text)?.let { m ->
            val grade = m.groupValues[1].ifEmpty { m.groupValues[3] }.toInt()
            val classNumber = m.groupValues[2].ifEmpty { m.groupValues[4] }.toInt()
            if (grade in 1..3 && classNumber in 1..20) grade to classNumber else null
        }

    companion object {
        /** "2학년 1반", "2학년1반" 또는 "2-1", "2-1반" */
        private val CLASS_PATTERN =
            Regex("(?<!\\d)([1-3])\\s*학년\\s*(\\d{1,2})\\s*반|(?<![\\d.\\-/])([1-3])\\s*-\\s*(\\d{1,2})(?![\\d.\\-/])\\s*반?")
        private val KOREAN_DATE_FORMAT = DateTimeFormatter.ofPattern("M월 d일")
        private val WEEKDAY_NAMES = mapOf(
            DayOfWeek.MONDAY to "월",
            DayOfWeek.TUESDAY to "화",
            DayOfWeek.WEDNESDAY to "수",
            DayOfWeek.THURSDAY to "목",
            DayOfWeek.FRIDAY to "금",
            DayOfWeek.SATURDAY to "토",
            DayOfWeek.SUNDAY to "일",
        )
    }
}
