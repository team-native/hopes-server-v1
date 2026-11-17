package kr.hs.gsm.hopes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kr.hs.gsm.hopes.feature.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DormitoryNavigationTest {
    private val mapper = jacksonObjectMapper()
    private val navigator = CampusNavigator(
        mapper.readValue<NavigationData>(Files.readString(Path.of("ops/campus/gsm-navigation.json"))),
        mapper.readValue<CampusData>(Files.readString(Path.of("ops/campus/gsm-campus.json"))),
    )
    @Test fun `dormitory rooms resolve with aliases and each floor transition includes both plans`() {
        val places = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(navigator.places("기숙사 401 앞"))["items"]
        assertTrue(places.any { it["name"].asText() == "401호" })
        val route = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(navigator.route("donghaeng-3F-room-301-1", "donghaeng-4F-room-401-1", false))
        assertEquals(listOf("donghaeng-3F", "donghaeng-4F"), route["legs"].map { it["floorPlan"]["id"].asText() })
        val elevator = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(navigator.route("donghaeng-3F-room-301-1", "donghaeng-5F-room-501-1", true))
        assertEquals("ELEVATOR", elevator["legs"][0]["next"]["type"].asText())
        assertEquals(listOf("donghaeng-3F", "donghaeng-5F"), elevator["legs"].map { it["floorPlan"]["id"].asText() })
        val ground = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(navigator.route("donghaeng-1F-01", "donghaeng-5F-room-518-1", false))
        assertEquals("donghaeng-1F", ground["legs"][0]["floorPlan"]["id"].asText())
        assertEquals("donghaeng-5F", ground["legs"].last()["floorPlan"]["id"].asText())
    }
    @Test fun `duplicate labels remain two candidates and nonexistent 315 is never invented`() {
        val duplicate = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(navigator.places("313호"))["items"]
        assertEquals(2, duplicate.size())
        assertEquals(0, mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(navigator.places("315호"))["items"].size())
        val chat = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(navigator.chatLocation("기숙사 313호 위치", null))
        assertEquals("SELECT_DESTINATION", chat["status"].asText())
        assertEquals(2, chat["candidates"].size())
    }
}
