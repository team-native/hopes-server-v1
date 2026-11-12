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
            """{"school":{"id":"test","name":"test","latitude":0,"longitude":0,"radiusMeters":100},"buildings":[{"id":1,"name":"test","latitude":0,"longitude":0}]}""",
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
}
