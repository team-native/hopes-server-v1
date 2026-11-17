package kr.hs.gsm.hopes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.time.Instant
import kr.hs.gsm.hopes.api.ApiException
import kr.hs.gsm.hopes.domain.ConversationRepository
import kr.hs.gsm.hopes.feature.CampusFacilityDto
import kr.hs.gsm.hopes.feature.CampusService
import kr.hs.gsm.hopes.feature.LocationRequest
import kr.hs.gsm.hopes.service.RateLimiter
import kr.hs.gsm.hopes.service.UserService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class CampusServiceTest {
    @Test
    fun `reference map comparison respects uncertainty and never persists device coordinates`() {
        val file = Files.createTempFile("hopes-campus-test", ".json")
        // Synthetic test coordinates only, not the school's surveyed location.
        Files.writeString(
            file,
            """{"school":{"id":"test","name":"test","latitude":0,"longitude":0,"radiusMeters":100,"source":"synthetic test"},"buildings":[{"id":1,"name":"test","latitude":0,"longitude":0,"coordinateSource":"synthetic test"}]}""",
        )
        try {
            val users = mock(UserService::class.java)
            val conversations = mock(ConversationRepository::class.java)
            val service =
                CampusService(
                    jacksonObjectMapper(),
                    file.toString(),
                    100.0,
                    120,
                    users,
                    conversations,
                    mock(RateLimiter::class.java),
                )
            fun check(lat: Double, accuracy: Double = 10.0) =
                service.check("test", LocationRequest(lat, 0.0, accuracy, Instant.now()))
            assertEquals("NEAR_SCHOOL", check(0.0).status)
            assertEquals("OUTSIDE", check(0.01).status)
            assertEquals("LOW_ACCURACY", check(0.0, 200.0).status)
            assertEquals("UNKNOWN", check(0.0009, 20.0).status)
            assertNull(check(0.0).matchedBuilding)
            assertEquals(false, service.plans(1)["available"])
            assertEquals("NOT_REGISTERED", service.plans(1)["reason"])
            assertThrows(ApiException::class.java) {
                service.check(
                    "test",
                    LocationRequest(0.0, 0.0, 10.0, Instant.now().minusSeconds(121)),
                )
            }
            assertThrows(ApiException::class.java) {
                service.check("test", LocationRequest(Double.NaN, 0.0, 10.0, Instant.now()))
            }
            verifyNoInteractions(users, conversations)
            assertFalse(Files.readString(file).contains("measuredAt"))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun service(json: String): CampusService {
        val file = Files.createTempFile("hopes-campus-test", ".json")
        try {
            Files.writeString(file, json)
            return CampusService(
                jacksonObjectMapper(),
                file.toString(),
                100.0,
                120,
                mock(UserService::class.java),
                mock(ConversationRepository::class.java),
                mock(RateLimiter::class.java),
            )
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `polygon boundary uses point in polygon with accuracy margin`() {
        // Synthetic L-shaped polygon near (0,0); about 111 m per 0.001 degree. Not the school.
        val service =
            service(
                """{"school":{"id":"t","name":"t","boundary":{"type":"POLYGON","source":"synthetic test","points":[
                {"latitude":0,"longitude":0},{"latitude":0,"longitude":0.002},
                {"latitude":0.001,"longitude":0.002},{"latitude":0.001,"longitude":0.001},
                {"latitude":0.002,"longitude":0.001},{"latitude":0.002,"longitude":0},
                {"latitude":0,"longitude":0}]}}}"""
            )
        fun check(lat: Double, lon: Double, accuracy: Double = 10.0) =
            service.check("t", LocationRequest(lat, lon, accuracy, Instant.now())).status
        assertEquals("NEAR_SCHOOL", check(0.0005, 0.0005))
        assertEquals("NEAR_SCHOOL", check(0.0015, 0.0005))
        // The notch of the L is outside even though it is inside the bounding box.
        assertEquals("OUTSIDE", check(0.0015, 0.0015))
        assertEquals("OUTSIDE", check(-0.001, 0.0005))
        // Inside but within accuracy of an edge (about 11 m from the south edge).
        assertEquals("UNKNOWN", check(0.0001, 0.0005, 20.0))
        assertEquals("UNKNOWN", check(-0.0001, 0.0005, 20.0))
        assertEquals("LOW_ACCURACY", check(0.0005, 0.0005, 150.0))
        val map = service.map()
        assertEquals(true, map["locationCheckAvailable"])
        @Suppress("UNCHECKED_CAST")
        val boundary = (map["school"] as Map<String, Any?>)["boundary"] as Map<String, Any?>
        assertEquals("POLYGON", boundary["type"])
        assertEquals(6, (boundary["points"] as List<*>).size)
    }

    @Test
    fun `new circle format matches legacy format`() {
        val legacy =
            service(
                """{"school":{"id":"t","name":"t","latitude":0,"longitude":0,"radiusMeters":100,"source":"s"}}"""
            )
        val circle =
            service(
                """{"school":{"id":"t","name":"t","boundary":{"type":"CIRCLE","source":"s",
                "center":{"latitude":0,"longitude":0},"radiusMeters":100}}}"""
            )
        for (lat in listOf(0.0, 0.0005, 0.0009, 0.0011, 0.01)) {
            val r = LocationRequest(lat, 0.0, 15.0, Instant.now())
            assertEquals(legacy.check("t", r).status, circle.check("t", r).status, "lat=$lat")
        }
    }

    @Test
    fun `boundary without coordinates or source is handled safely`() {
        val noBoundary = service("""{"school":{"id":"t","name":"t"}}""")
        assertEquals(false, noBoundary.map()["locationCheckAvailable"])
        val e =
            assertThrows(ApiException::class.java) {
                noBoundary.check("t", LocationRequest(0.0, 0.0, 10.0, Instant.now()))
            }
        assertEquals(503, e.status.value())
        for (bad in
            listOf(
                // No source
                """{"school":{"id":"t","name":"t","latitude":0,"longitude":0,"radiusMeters":100}}""",
                // Too few points
                """{"school":{"id":"t","name":"t","boundary":{"type":"POLYGON","source":"s","points":[{"latitude":0,"longitude":0},{"latitude":0,"longitude":1}]}}}""",
                // Self-intersecting bow tie
                """{"school":{"id":"t","name":"t","boundary":{"type":"POLYGON","source":"s","points":[{"latitude":0,"longitude":0},{"latitude":0.001,"longitude":0.001},{"latitude":0,"longitude":0.001},{"latitude":0.001,"longitude":0}]}}}""",
                // Legacy fields and boundary together
                """{"school":{"id":"t","name":"t","latitude":0,"longitude":0,"radiusMeters":1,"source":"s","boundary":{"type":"CIRCLE","source":"s","center":{"latitude":0,"longitude":0},"radiusMeters":1}}}""",
            )) assertThrows(IllegalArgumentException::class.java, { service(bad) }, bad)
    }

    @Test
    fun `floor plans are withheld unless approved and restricted floors hide facilities`() {
        val service =
            service(
                """{"school":{"id":"t","name":"t"},"buildings":[
                {"id":1,"name":"A","floors":[{"floor":"1F","facilities":["lab"]},
                  {"floor":"3F","facilities":["dorm 301"],"facilitiesPublic":false}],
                 "floorPlans":[{"id":"a1","floor":"1F","label":"A 1F","url":"https://example.com/a1.png","publicationApproved":true},
                  {"id":"a3","floor":"3F","label":"A 3F","url":"https://example.com/secret-a3.png"}]},
                {"id":2,"name":"B","floors":[{"floor":"1F","facilities":["hall"]}],
                 "floorPlans":[{"id":"b1","floor":"1F","label":"B 1F"}]},
                {"id":3,"name":"C"},
                {"id":4,"name":"D","publicationApproved":true,
                 "floorPlans":[{"id":"d1","floor":"1F","label":"D 1F","url":"https://example.com/d1.png"},
                  {"id":"d2","floor":"2F","label":"D 2F","url":"https://example.com/d2.png","publicationApproved":false}]}]}"""
            )
        val a = service.plans(1)
        assertEquals(true, a["available"])
        assertEquals("AVAILABLE", a["reason"])
        val items = a["items"] as List<*>
        assertEquals(1, items.size)
        assertEquals(listOf("lab"), (items[0] as Map<*, *>)["facilities"])
        assertFalse(a.toString().contains("secret-a3"))

        val b = service.plans(2)
        assertEquals(false, b["available"])
        assertEquals("NOT_APPROVED", b["reason"])
        assertEquals(emptyList<Any>(), b["items"])
        assertEquals("NOT_REGISTERED", service.plans(3)["reason"])
        assertEquals(false, service.plans(3)["available"])
        // Building-level approval still works (legacy), and a per-plan false overrides it.
        assertEquals(
            listOf("d1"),
            (service.plans(4)["items"] as List<*>).map { (it as Map<*, *>)["id"] },
        )
        assertThrows(ApiException::class.java) { service.plans(99) }

        val map = service.map()
        assertFalse(map.toString().contains("dorm 301"))
        assertFalse(map.toString().contains("secret-a3"))
        assertTrue(map.toString().contains("lab"))
        assertEquals(true, map["floorPlansAvailable"])
        // Without a boundary the location check is unavailable but map/plans still work.
        assertEquals(false, map["locationCheckAvailable"])
    }

    @Test
    fun `invalid building reference data is rejected at startup`() {
        for (bad in
            listOf(
                // Approved plan without URL
                """{"school":{"id":"t","name":"t"},"buildings":[{"id":1,"name":"A","floorPlans":[{"id":"p","floor":"1F","label":"x","publicationApproved":true}]}]}""",
                // Non-HTTPS URL
                """{"school":{"id":"t","name":"t"},"buildings":[{"id":1,"name":"A","floorPlans":[{"id":"p","floor":"1F","label":"x","url":"http://x"}]}]}""",
                // Plan for an unregistered floor
                """{"school":{"id":"t","name":"t"},"buildings":[{"id":1,"name":"A","floors":[{"floor":"1F"}],"floorPlans":[{"id":"p","floor":"9F","label":"x"}]}]}""",
                // Building coordinates without source
                """{"school":{"id":"t","name":"t"},"buildings":[{"id":1,"name":"A","latitude":0,"longitude":0}]}""",
                // Only one of latitude/longitude
                """{"school":{"id":"t","name":"t"},"buildings":[{"id":1,"name":"A","latitude":0,"coordinateSource":"s"}]}""",
                // Duplicate floor
                """{"school":{"id":"t","name":"t"},"buildings":[{"id":1,"name":"A","floors":[{"floor":"1F"},{"floor":"1F"}]}]}""",
            )) assertThrows(IllegalArgumentException::class.java, { service(bad) }, bad)
    }

    @Test
    fun `registered school reference data exposes public floor plans without private dormitory floors`() {
        val service = service(Files.readString(java.nio.file.Path.of("ops/campus/gsm-campus.json")))
        val map = service.map()
        @Suppress("UNCHECKED_CAST") val buildings = map["buildings"] as List<Map<String, Any?>>
        assertEquals(listOf("본관", "실습동", "금봉관", "동행관"), buildings.map { it["name"] })
        // No sourced coordinates yet: nothing is invented and location comparison stays
        // unavailable.
        assertEquals(false, map["locationCheckAvailable"])
        assertTrue(buildings.all { it["latitude"] == null && it["longitude"] == null })
        val e =
            assertThrows(ApiException::class.java) {
                service.check("t", LocationRequest(35.0, 126.0, 10.0, Instant.now()))
            }
        assertEquals(503, e.status.value())
        // Official public floor plans are published; residential floors remain hidden.
        assertEquals(true, map["floorPlansAvailable"])
        for (id in 1L..4L) {
            val plans = service.plans(id)
            assertEquals(true, plans["available"])
            // Dormitory 3F overview is now published; private room records stay excluded.
            // Dormitory 4F overview is now published; private room records stay excluded.
            // Dormitory 5F overview is now published; private room records stay excluded.
        }
        // Dormitory floors list floor names only.
        val text = map.toString()
        assertTrue(text.contains("AI모델링실") && text.contains("빅데이터분석실습실"))
        assertTrue(text.contains("301호") && text.contains("401호") && text.contains("501호"))
        assertFalse(text.contains("자기주도학습실") || text.contains("사감실"))
    }

    @Test
    fun `public facility search filters building floor and keyword without private notes`() {
        val service =
            service(
                """{"school":{"id":"t","name":"t"},"buildings":[{"id":1,"name":"본관","floors":[{"floor":"1F","facilities":["성고충상담실/보건실","교무실"],"note":"private note"},{"floor":"3F","facilities":["SECRET_ROOM"],"facilitiesPublic":false}]}]}"""
            )
        @Suppress("UNCHECKED_CAST")
        val rows = service.facilities(" 보건실 ", 1, "1f")["items"] as List<CampusFacilityDto>
        assertEquals(listOf(CampusFacilityDto(1, "본관", "1F", "성고충상담실/보건실")), rows)
        assertEquals(0, service.facilities("없는 시설", null, null)["total"])
        val all = service.facilities(null, null, null).toString()
        assertFalse(all.contains("SECRET_ROOM") || all.contains("private note"))
        assertTrue(service.facilityContext("보건실 어디야?").contains("본관 1F: 성고충상담실/보건실"))
        val context = service.facilityContext("본관 안내")
        assertFalse(context.contains("SECRET_ROOM") || context.contains("private note"))
        assertEquals("", service.facilityContext("없는 시설"))
        assertEquals(
            400,
            assertThrows(ApiException::class.java) {
                    service.facilities("x".repeat(101), null, null)
                }
                .status
                .value(),
        )
        assertEquals(
            404,
            assertThrows(ApiException::class.java) { service.facilities(null, 99, null) }
                .status
                .value(),
        )
    }
}
