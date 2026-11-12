package kr.hs.gsm.hopes.feature

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.math.*
import kr.hs.gsm.hopes.domain.ConversationRepository
import kr.hs.gsm.hopes.service.RateLimiter
import kr.hs.gsm.hopes.service.UserService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*

data class SchoolMap(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
) {
    val boundary: Map<String, Any>
        get() = mapOf("type" to "CIRCLE", "radiusMeters" to radiusMeters)
}

data class FloorPlan(
    val id: String,
    val floor: String,
    val label: String,
    val url: String,
    val facilities: List<String> = emptyList(),
)

data class CampusBuilding(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val publicationApproved: Boolean = false,
    val floorPlans: List<FloorPlan> = emptyList(),
)

data class CampusData(val school: SchoolMap, val buildings: List<CampusBuilding> = emptyList())

data class LocationRequest(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Double,
    val measuredAt: Instant,
    val clientRequestId: String? = null,
)

data class LocationResult(
    val status: String,
    val accuracyMeters: Double,
    val message: String,
    val floorPlansAvailable: Boolean,
    val matchedBuilding: Long? = null,
)

/**
 * Only static campus reference data is loaded. Device coordinates never enter persistence or
 * chat/Discord.
 */
@Service
class CampusService(
    mapper: ObjectMapper,
    @Value("\${hopes.campus.data-path:}") path: String,
    @Value("\${hopes.campus.max-accuracy-meters:100}") private val maxAccuracy: Double,
    @Value("\${hopes.campus.max-age-seconds:120}") private val maxAge: Long,
    private val users: UserService,
    private val conversations: ConversationRepository,
    private val limits: RateLimiter,
) {
    private val data: CampusData? =
        path
            .takeIf { it.isNotBlank() }
            ?.let {
                Files.newBufferedReader(Path.of(it)).use { reader ->
                    mapper.readValue<CampusData>(reader)
                }
            }

    init {
        require(maxAccuracy.isFinite() && maxAccuracy > 0 && maxAge > 0)
        data?.let { d ->
            require(
                validCoordinates(d.school.latitude, d.school.longitude) &&
                    d.school.radiusMeters.isFinite() &&
                    d.school.radiusMeters > 0
            )
            require(d.buildings.map { it.id }.distinct().size == d.buildings.size)
            d.buildings.forEach { b ->
                require(b.id > 0 && validCoordinates(b.latitude, b.longitude))
                require(b.floorPlans.all { it.url.startsWith("https://") })
            }
        }
    }

    private fun registered() =
        data ?: fail(HttpStatus.SERVICE_UNAVAILABLE, "학교 기준 좌표가 아직 등록되지 않았습니다")

    fun map(): Map<String, Any> {
        val d = registered()
        return mapOf(
            "school" to d.school,
            "buildings" to
                d.buildings.map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "latitude" to it.latitude,
                        "longitude" to it.longitude,
                    )
                },
            "floorPlansAvailable" to
                d.buildings.any { it.publicationApproved && it.floorPlans.isNotEmpty() },
        )
    }

    fun check(email: String, r: LocationRequest): LocationResult {
        limits.checkFeature("location-check", email, 30)
        if (
            !validCoordinates(r.latitude, r.longitude) ||
                !r.horizontalAccuracyMeters.isFinite() ||
                r.horizontalAccuracyMeters < 0
        )
            fail(HttpStatus.BAD_REQUEST, "좌표와 정확도를 확인해주세요")
        val age = Duration.between(r.measuredAt, Instant.now()).seconds
        if (age < -30) fail(HttpStatus.BAD_REQUEST, "미래 측정 시각입니다")
        if (age > maxAge) fail(HttpStatus.UNPROCESSABLE_ENTITY, "위치를 다시 측정해주세요")
        val d = registered()
        val distance = distance(r.latitude, r.longitude, d.school.latitude, d.school.longitude)
        val status =
            when {
                r.horizontalAccuracyMeters > maxAccuracy -> "LOW_ACCURACY"
                distance - r.horizontalAccuracyMeters > d.school.radiusMeters -> "OUTSIDE"
                distance + r.horizontalAccuracyMeters <= d.school.radiusMeters -> "NEAR_SCHOOL"
                else -> "UNKNOWN"
            }
        val message =
            when (status) {
                "LOW_ACCURACY" -> "위치 오차가 커서 판단하기 어려워. 다시 측정해줘."
                "OUTSIDE" -> "측정 위치는 학교 영역 밖이야."
                "NEAR_SCHOOL" -> "학교 부근이야. GPS만으로 층이나 교실은 확인할 수 없어."
                else -> "오차 범위가 학교 경계와 겹쳐서 확실히 판단하기 어려워."
            }
        return LocationResult(
            status,
            r.horizontalAccuracyMeters,
            message,
            d.buildings.any { it.publicationApproved && it.floorPlans.isNotEmpty() },
        )
    }

    fun chat(email: String, id: Long, r: LocationRequest): Map<String, Any> {
        val uid = users.requireUser(email).id!!
        if (conversations.findByIdAndUserId(id, uid) == null)
            fail(HttpStatus.NOT_FOUND, "지난 대화를 찾을 수 없습니다")
        return mapOf("chatId" to id, "locationResult" to check(email, r), "cardType" to "LOCATION")
    }

    fun plans(id: Long): Map<String, Any> {
        val b =
            registered().buildings.find { it.id == id }
                ?: fail(HttpStatus.NOT_FOUND, "건물을 찾을 수 없습니다")
        if (b.floorPlans.isNotEmpty() && !b.publicationApproved)
            fail(HttpStatus.FORBIDDEN, "공개 승인되지 않은 도면입니다")
        return mapOf(
            "available" to b.floorPlans.isNotEmpty(),
            "items" to b.floorPlans,
            "reason" to if (b.floorPlans.isEmpty()) "NOT_REGISTERED" else "AVAILABLE",
        )
    }

    private fun validCoordinates(lat: Double, lon: Double) =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    private fun distance(lat: Double, lon: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat)
        val dLon = Math.toRadians(lon2 - lon)
        val a =
            sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6371000.0 * 2 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1 - a).coerceIn(0.0, 1.0)))
    }
}

@RestController
@RequestMapping("/api")
@Tag(name = "현재 위치")
@SecurityRequirement(name = "bearerAuth")
class CampusController(private val service: CampusService) {
    @GetMapping("/campus/map") fun map() = service.map()

    @PostMapping("/campus/location-check")
    fun check(a: Authentication, @RequestBody r: LocationRequest) = service.check(a.name, r)

    @PostMapping("/chats/{id}/location-check")
    fun chat(a: Authentication, @PathVariable id: Long, @RequestBody r: LocationRequest) =
        service.chat(a.name, id, r)

    @GetMapping("/campus/buildings/{buildingId}/floor-plans")
    fun plans(@PathVariable buildingId: Long) = service.plans(buildingId)
}
