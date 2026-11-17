package kr.hs.gsm.hopes

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kr.hs.gsm.hopes.domain.User
import kr.hs.gsm.hopes.domain.UserRepository
import kr.hs.gsm.hopes.security.AccessTokenService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

@SpringBootTest(
    properties =
        [
            "hopes.ai.enabled=false",
            "hopes.mail.enabled=false",
            "hopes.campus.data-path=ops/campus/gsm-campus.json",
            "hopes.campus.navigation-path=ops/campus/gsm-navigation.json",
            "spring.datasource.url=jdbc:h2:mem:published-plans;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        ]
)
@AutoConfigureMockMvc
class PublishedFloorPlansTest
@Autowired
constructor(
    val mvc: MockMvc,
    val mapper: ObjectMapper,
    val users: UserRepository,
    val tokens: AccessTokenService,
) {
    @Test
    fun `second to third floor route returns both plans and dormitory questions stay floor specific`() {
        val user = users.saveAndFlush(User(email = "floor-route@example.invalid", username = "floor-route", nickname = "QA"))
        val auth = "Bearer " + tokens.create(user.email, user.tokenVersion)
        fun send(path: String, body: String, expected: Int = 200): com.fasterxml.jackson.databind.JsonNode {
            val response = mvc.perform(post(path).header("Authorization", auth).contentType("application/json").content(body)).andReturn().response
            assertEquals(expected, response.status, response.contentAsString)
            return mapper.readTree(response.contentAsString)
        }
        val id = send("/api/chats", "{}", 201)["id"].asLong()
        val path = "/api/chats/$id/messages"
        val route = send(path, """{"content":"2층에서 3층 홈베이스로 가고 싶어","destinationPlaceId":"main-3F-homebase","navigationOrigin":"main-2F-homebase"}""")["messages"].last()["location"]["route"]
        assertEquals(listOf("main-2F", "main-3F"), route["legs"].map { it["floorPlan"]["id"].asText() })
        val dorm = send(path, """{"content":"기숙사 4층 위치가 어디야?"}""")["messages"].last()
        assertEquals("FLOOR_OVERVIEW", dorm["location"]["status"].asText())
        assertTrue(dorm["content"].asText().contains("동행관 4층"))
        assertFalse(dorm["content"].asText().contains("1층"))
        val second = send(path, """{"content":"2층 없어?"}""")["messages"].last()
        assertEquals("FLOOR_OVERVIEW", second["location"]["status"].asText())
        assertEquals("donghaeng-2F", second["location"]["floorPlans"][0]["id"].asText())
        assertEquals(3, send(path, """{"content":"기숙사 3층"}""")["messages"].last()["location"]["floorPlans"][0]["floor"].asText().removeSuffix("F").toInt())
        listOf("기숙사 2층 도면 좀 보여줘", "기숙사 3층 도면 보여줘", "동행관 2층 배치도 보여줘").forEach { question ->
            val overview = send(path, mapper.writeValueAsString(mapOf("content" to question)))["messages"].last()
            assertEquals("FLOOR_OVERVIEW", overview["location"]["status"].asText(), question)
            assertEquals(1, overview["location"]["floorPlans"].size(), question)
            assertFalse(overview["content"].asText().contains("확인하지 못했어"), question)
        }
        val library = send(path, """{"content":"도서관"}""")["messages"].last()
        assertEquals("도서실", library["location"]["candidates"][0]["place"]["name"].asText())
        val search = mvc.perform(get("/api/campus/navigation/places").param("q", "2-3반 앞").header("Authorization", auth)).andReturn().response
        assertEquals(200, search.status)
        assertTrue(mapper.readTree(search.contentAsString)["items"].any { it["name"].asText() == "2학년 3반" })
    }

    @Test
    fun `homebase followups use main building floors and never invent dormitory answers`() {
        val user = users.saveAndFlush(User(email = "homebase@example.invalid", username = "homebase", nickname = "QA"))
        val auth = "Bearer " + tokens.create(user.email, user.tokenVersion)
        fun send(path: String, body: String, expected: Int = 200): com.fasterxml.jackson.databind.JsonNode {
            val response = mvc.perform(post(path).header("Authorization", auth).contentType("application/json").content(body)).andReturn().response
            assertEquals(expected, response.status, response.contentAsString)
            return mapper.readTree(response.contentAsString)
        }
        val id = send("/api/chats", "{}", 201)["id"].asLong()
        val path = "/api/chats/$id/messages"
        val first = send(path, """{"content":"홈베이스 위치 알려줘"}""")["messages"].last()
        assertEquals("SELECT_DESTINATION", first["location"]["status"].asText())
        assertEquals(setOf("2F", "3F", "4F"), first["location"]["candidates"].map { it["place"]["floor"].asText() }.toSet())
        val followup = send(path, """{"content":"4층은 없어?"}""")["messages"].last()
        assertEquals("DESTINATION_READY", followup["location"]["status"].asText())
        assertEquals("main-4F-homebase", followup["location"]["candidates"][0]["place"]["id"].asText())
        assertFalse(followup["content"].asText().contains("기숙사"))
        val explicit = send(path, """{"content":"본관 4층 홈베이스는 없어?"}""")["messages"].last()
        assertEquals("main-4F", explicit["location"]["candidates"][0]["floorPlan"]["id"].asText())
        val unknown = send(path, """{"content":"본관 4층 수영장 위치 알려줘"}""")["messages"].last()
        assertEquals("LOCATION_UNVERIFIED", unknown["location"]["status"].asText())
        org.mockito.Mockito.verifyNoInteractions(discord)
    }

    @org.springframework.boot.test.mock.mockito.MockBean
    lateinit var discord: kr.hs.gsm.hopes.service.DiscordQuestionLogService
    @Test
    fun `chat location cards persist destination but never persist origin coordinates`() {
        val user =
            users.saveAndFlush(
                User(email = "chat-map@example.invalid", username = "chat-map", nickname = "QA")
            )
        val auth = "Bearer " + tokens.create(user.email, user.tokenVersion)
        fun send(
            path: String,
            body: String,
            expected: Int = 200,
        ): com.fasterxml.jackson.databind.JsonNode {
            val response =
                mvc.perform(
                        post(path)
                            .header("Authorization", auth)
                            .contentType("application/json")
                            .content(body)
                    )
                    .andReturn()
                    .response
            assertEquals(expected, response.status, response.contentAsString)
            return mapper.readTree(response.contentAsString)
        }
        val id = send("/api/chats", "{}", 201)["id"].asLong()
        val path = "/api/chats/$id/messages"
        val first = send(path, """{"content":"보건실 위치 알려줘"}""")
        val card = first["messages"].last()["location"]
        assertEquals("DESTINATION_READY", card["status"].asText())
        assertEquals("main-1F", card["candidates"][0]["floorPlan"]["id"].asText())
        assertEquals("main-1F-02", card["candidates"][0]["place"]["id"].asText())
        val body =
            """{"content":"여기서 보건실까지 안내해줘","destinationPlaceId":"main-1F-02","navigationOrigin":"main-3F@120,104","clientRequestId":"route-card"}"""
        val routed = send(path, body)
        assertEquals("ROUTE_READY", routed["messages"].last()["location"]["status"].asText())
        assertEquals(2, routed["messages"].last()["location"]["route"]["legs"].size())
        val again = send(path, body)
        assertEquals(routed["messages"].size(), again["messages"].size())
        val loaded =
            mapper.readTree(
                mvc.perform(get("/api/chats/$id").header("Authorization", auth))
                    .andReturn()
                    .response
                    .contentAsString
            )
        assertEquals("DESTINATION_READY", loaded["messages"].last()["location"]["status"].asText())
        assertFalse(loaded["messages"].last()["location"].has("route"))
        send(path, """{"content":"찾아줘","destinationPlaceId":"donghaeng-3F-01"}""", 404)
        org.mockito.Mockito.verifyNoInteractions(discord)
    }

    @Test
    fun `published route includes actual images and health office marker on first floor`() {
        val user =
            users.saveAndFlush(
                User(email = "plans@example.invalid", username = "plans", nickname = "QA")
            )
        val auth = "Bearer " + tokens.create(user.email, user.tokenVersion)
        val path = "/api/campus/floor-plans/main-1F/image"
        assertEquals(401, mvc.perform(get(path)).andReturn().response.status)
        val response =
            mvc.perform(
                    get("/api/campus/navigation/route?from=main-3F-01&to=main-1F-02")
                        .header("Authorization", auth)
                )
                .andReturn()
                .response
        assertEquals(200, response.status)
        val route = mapper.readTree(response.contentAsString)
        assertEquals(
            listOf("main-3F", "main-1F"),
            route["legs"].map { it["floorPlan"]["id"].asText() },
        )
        assertTrue(route["distanceEstimated"].asBoolean())
        route["legs"].forEach { leg ->
            val plan = leg["floorPlan"]
            val result =
                mvc.perform(
                        get(java.net.URI(plan["url"].asText()).path).header("Authorization", auth)
                    )
                    .andReturn()
                    .response
            assertEquals(200, result.status)
            val png = ImageIO.read(ByteArrayInputStream(result.contentAsByteArray))
            assertEquals(plan["width"].asInt(), png.width)
            assertEquals(plan["height"].asInt(), png.height)
        }
        val dorm =
            mvc.perform(
                    get("/api/campus/floor-plans/donghaeng-2F/image").header("Authorization", auth)
                )
                .andReturn()
                .response
        val png = ImageIO.read(ByteArrayInputStream(dorm.contentAsByteArray))
        for (x in 0 until 400 step 10) for (y in 0 until png.height step 10) assertEquals(
            0xffffff,
            png.getRGB(x, y) and 0xffffff,
        )
        assertEquals(
            200,
            mvc.perform(
                    get("/api/campus/floor-plans/donghaeng-3F/image").header("Authorization", auth)
                )
                .andReturn()
                .response
                .status,
        )
    }
}
