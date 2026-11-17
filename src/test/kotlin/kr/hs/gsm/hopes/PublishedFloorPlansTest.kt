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
            404,
            mvc.perform(
                    get("/api/campus/floor-plans/donghaeng-3F/image").header("Authorization", auth)
                )
                .andReturn()
                .response
                .status,
        )
    }
}
