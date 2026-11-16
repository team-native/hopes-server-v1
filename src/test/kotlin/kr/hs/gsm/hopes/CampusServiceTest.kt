package kr.hs.gsm.hopes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.time.Instant
import kr.hs.gsm.hopes.api.ApiException
import kr.hs.gsm.hopes.domain.ConversationRepository
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
            """{"school":{"id":"test","name":"test","latitude":0,"longitude":0,"radiusMeters":100,"source":"synthetic test"},"buildings":[{"id":1,"name":"test","latitude":0,"longitude":0}]}""",
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
            service("""{"school":{"id":"t","name":"t","latitude":0,"longitude":0,"radiusMeters":100,"source":"s"}}""")
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
}
