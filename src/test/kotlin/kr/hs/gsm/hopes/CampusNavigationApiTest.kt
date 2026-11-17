package kr.hs.gsm.hopes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kr.hs.gsm.hopes.domain.User
import kr.hs.gsm.hopes.domain.UserRepository
import kr.hs.gsm.hopes.security.AccessTokenService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 실제 길찾기 자료를 HTTP로 확인한다. 저장소 자료는 공개 승인 전이라, 테스트에서만 도면을 승인된 것으로 바꾼 복사본을 쓴다.
 */
@SpringBootTest(
    properties =
        [
            "spring.datasource.url=jdbc:h2:mem:navigation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "hopes.ai.enabled=true",
            "hopes.ai.chunks-path=classpath:data/test_rag_chunks.jsonl",
            "hopes.ai.cache-path=./target/navigation/embedding.json",
            "hopes.attachments.directory=./target/navigation/files",
        ]
)
@AutoConfigureMockMvc
@Import(HopesAiIntegrationTest.FakeAiConfig::class)
class CampusNavigationApiTest
@Autowired
constructor(
    private val mvc: MockMvc,
    private val mapper: ObjectMapper,
    private val users: UserRepository,
    private val tokens: AccessTokenService,
) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun approvedCopy(r: DynamicPropertyRegistry) {
            val json = jacksonObjectMapper()
            val campus = json.readTree(Path.of("ops/campus/gsm-campus.json").toFile()) as ObjectNode
            campus["buildings"].forEach { b ->
                b["floorPlans"].forEach { plan ->
                    // 동행관 2F만 승인하지 않은 상태로 두어, 미승인 도면이 응답에서 빠지는지 확인한다.
                    if (plan["id"].asText() != "donghaeng-2F") {
                        (plan as ObjectNode).put("publicationApproved", true)
                        plan.put("url", "https://example.test/${plan["id"].asText()}.png")
                    } else (plan as ObjectNode).put("publicationApproved", false)
                }
            }
            val dir = Files.createDirectories(Path.of("target/navigation"))
            val file = dir.resolve("campus-approved.json")
            json.writeValue(file.toFile(), campus)
            r.add("hopes.campus.data-path") { file.toString() }
            r.add("hopes.campus.navigation-path") { "ops/campus/gsm-navigation.json" }
        }
    }

    private fun token(): String {
        val key = UUID.randomUUID().toString()
        val u = users.save(User(email = "$key@gsm.hs.kr", username = key, nickname = key, passwordHash = "unused", cohort = 10))
        return "Bearer ${tokens.create(u.email, u.tokenVersion)}"
    }

    private fun call(path: String, token: String?, expected: Int = 200): JsonNode {
        val request = get(path)
        token?.let { request.header("Authorization", it) }
        val body = mvc.perform(request).andExpect(status().`is`(expected)).andReturn().response
        body.characterEncoding = "UTF-8"
        return mapper.readTree(body.contentAsString.ifBlank { "{}" })
    }

    private fun placeId(token: String, map: String, name: String) =
        call("/api/campus/navigation/places?q=$name", token)["items"]
            .single { it["floorPlanId"].asText() == map && it["name"].asText() == name }["id"]
            .asText()

    @Test
    fun `navigation requires login and lists only approved floor plans`() {
        call("/api/campus/navigation/places", null, 401)
        call("/api/campus/navigation/route?from=main-3F-01&to=main-1F-13", null, 401)
        val t = token()
        val all = call("/api/campus/navigation/places", t)
        assertTrue(all["available"].asBoolean())
        val maps = all["items"].map { it["floorPlanId"].asText() }.toSet()
        assertTrue("main-1F" in maps && "geumbong-4F" in maps && "donghaeng-1F" in maps)
        assertFalse("donghaeng-2F" in maps)
        // 미승인 도면의 장소는 길찾기 대상도 아니다.
        call("/api/campus/navigation/route?from=main-1F-13&to=donghaeng-2F-03", t, 404)
    }

    @Test
    fun `route response gives each floor plan with a drawable path`() {
        val t = token()
        val from = placeId(t, "main-3F", "2학년 1반")
        val to = placeId(t, "main-1F", "교무실")
        val r = call("/api/campus/navigation/route?from=$from&to=$to", t)
        assertEquals("2학년 1반", r["from"]["name"].asText())
        assertEquals("main-3F", r["from"]["floorPlanId"].asText())
        assertEquals(63.0, r["from"]["area"]["width"].asDouble())
        val legs = r["legs"]
        assertEquals(2, legs.size())
        val plan = legs[0]["floorPlan"]
        assertEquals("https://example.test/main-3F.png", plan["url"].asText())
        assertEquals(844, plan["width"].asInt())
        assertEquals("STAIR", legs[0]["next"]["type"].asText())
        assertTrue(legs[1]["next"].isNull)
        legs.forEach { leg ->
            assertTrue(leg["path"].size() >= 2)
            leg["path"].forEach { p ->
                assertTrue(p["x"].asDouble() in 0.0..leg["floorPlan"]["width"].asDouble())
                assertTrue(p["y"].asDouble() in 0.0..leg["floorPlan"]["height"].asDouble())
            }
        }
        assertTrue(r["distanceMeters"].asInt() > 0 && r["estimatedSeconds"].asInt() > 0)

        // 엘리베이터만, 도면 위 좌표 출발
        val elevator = call("/api/campus/navigation/route?from=main-4F@200,104&to=$to&elevatorOnly=true", t)
        assertEquals("ELEVATOR", elevator["legs"][0]["next"]["type"].asText())
        assertFalse(elevator["from"].has("name"))
        assertEquals("main-4F", elevator["from"]["floorPlanId"].asText())
    }

    @Test
    fun `invalid route requests return clear errors`() {
        val t = token()
        val office = placeId(t, "main-1F", "교무실")
        call("/api/campus/navigation/route?from=$office&to=$office", t, 400)
        call("/api/campus/navigation/route?from=nope&to=$office", t, 404)
        call("/api/campus/navigation/route?from=main-1F@-5,10&to=$office", t, 400)
        call("/api/campus/navigation/route?to=$office", t, 400)
        val library = placeId(t, "geumbong-3F", "도서실")
        call("/api/campus/navigation/route?from=$office&to=$library&elevatorOnly=true", t, 422)
    }
}
