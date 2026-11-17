package kr.hs.gsm.hopes.feature

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kr.hs.gsm.hopes.domain.ConversationRepository
import kr.hs.gsm.hopes.service.RateLimiter
import kr.hs.gsm.hopes.service.UserService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*

data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * 학교 경계. CIRCLE은 center + radiusMeters, POLYGON은 points(3개 이상, 시계/반시계 무관)로 표현한다. 좌표는 출처를 확인할 수 있는
 * 값만 등록하며 source가 비어 있으면 로딩을 거부한다.
 */
data class CampusBoundary(
    val type: String,
    val center: GeoPoint? = null,
    val radiusMeters: Double? = null,
    val points: List<GeoPoint> = emptyList(),
    val source: String? = null,
)

/**
 * 기존 형식(latitude/longitude/radiusMeters 평면 필드)과 새 boundary 형식을 모두 받는다. 둘 중 하나만 쓸 수 있고, 좌표가 하나도 없으면
 * 지도·시설 조회만 가능하고 위치 비교는 503이다.
 */
data class SchoolMap(
    val id: String,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Double? = null,
    val boundary: CampusBoundary? = null,
    val source: String? = null,
) {
    internal fun resolvedBoundary(): CampusBoundary? {
        val legacy = latitude != null || longitude != null || radiusMeters != null
        require(!(legacy && boundary != null)) { "school 평면 좌표와 boundary는 함께 쓸 수 없습니다" }
        if (boundary != null) return boundary.copy(source = boundary.source ?: source)
        if (!legacy) return null
        require(latitude != null && longitude != null && radiusMeters != null) {
            "school latitude, longitude, radiusMeters를 모두 입력해야 합니다"
        }
        return CampusBoundary(
            "CIRCLE",
            GeoPoint(latitude, longitude),
            radiusMeters,
            source = source,
        )
    }
}

/** 층별 시설 목록. facilitiesPublic=false(예: 공개 승인 전인 기숙사 층)이면 층 이름만 응답하고 시설 목록은 응답하지 않는다. */
data class CampusFloor(
    val floor: String,
    val facilities: List<String> = emptyList(),
    val facilitiesPublic: Boolean = true,
    val note: String? = null,
)

/**
 * 도면 한 장. publicationApproved가 없으면 건물의 publicationApproved를 따른다. 승인되지 않은 도면은 URL을 포함해 어떤 정보도 응답하지
 * 않는다. url은 승인된 도면에만 필수다.
 */
data class FloorPlan(
    val id: String,
    val floor: String,
    val label: String,
    val url: String? = null,
    val facilities: List<String> = emptyList(),
    val publicationApproved: Boolean? = null,
    val source: String? = null,
)

data class CampusBuilding(
    val id: Long,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val coordinateSource: String? = null,
    val publicationApproved: Boolean = false,
    val floors: List<CampusFloor> = emptyList(),
    val floorPlans: List<FloorPlan> = emptyList(),
    val source: String? = null,
) {
    internal fun approved(plan: FloorPlan) = plan.publicationApproved ?: publicationApproved

    internal fun approvedPlans() = floorPlans.filter { approved(it) }
}

data class CampusData(
    val school: SchoolMap,
    val buildings: List<CampusBuilding> = emptyList(),
    val source: String? = null,
    val note: String? = null,
)

data class CampusFacilityDto(
    val buildingId: Long,
    val buildingName: String,
    val floor: String,
    val name: String,
)

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

    private val boundary: CampusBoundary? = data?.school?.resolvedBoundary()
    private val geometry: CampusGeometry? = boundary?.let { CampusGeometry.from(it) }

    init {
        require(maxAccuracy.isFinite() && maxAccuracy > 0 && maxAge > 0)
        data?.let { d ->
            require(d.buildings.map { it.id }.distinct().size == d.buildings.size)
            d.buildings.forEach { b ->
                require(b.id > 0 && b.name.isNotBlank()) { "건물 id/name을 확인해주세요" }
                require((b.latitude == null) == (b.longitude == null)) {
                    "건물 ${b.id}: latitude와 longitude는 함께 입력해야 합니다"
                }
                if (b.latitude != null && b.longitude != null) {
                    require(validCoordinates(b.latitude, b.longitude)) { "건물 ${b.id}: 잘못된 좌표입니다" }
                    require(!b.coordinateSource.isNullOrBlank()) {
                        "건물 ${b.id}: 좌표 출처(coordinateSource)가 필요합니다"
                    }
                }
                val floorNames = b.floors.map { it.floor }
                require(floorNames.distinct().size == floorNames.size) { "건물 ${b.id}: 층이 중복됩니다" }
                require(b.floorPlans.map { it.id }.distinct().size == b.floorPlans.size) {
                    "건물 ${b.id}: 도면 id가 중복됩니다"
                }
                b.floorPlans.forEach { plan ->
                    require(plan.url == null || plan.url.startsWith("https://")) {
                        "도면 ${plan.id}: url은 HTTPS여야 합니다"
                    }
                    require(!b.approved(plan) || plan.url != null) {
                        "도면 ${plan.id}: 공개 승인된 도면에는 url이 필요합니다"
                    }
                    require(floorNames.isEmpty() || plan.floor in floorNames) {
                        "도면 ${plan.id}: 등록되지 않은 층(${plan.floor})입니다"
                    }
                }
            }
        }
    }

    /** 길찾기가 도면 공개 승인·층 공개 여부를 같은 기준으로 판단하도록 원본 자료를 넘긴다. */
    internal fun referenceData(): CampusData? = data

    private fun registered() =
        data ?: fail(HttpStatus.SERVICE_UNAVAILABLE, "학교 기준 자료가 아직 등록되지 않았습니다")

    fun map(): Map<String, Any?> {
        val d = registered()
        return mapOf(
            "school" to schoolView(d.school),
            "locationCheckAvailable" to (geometry != null),
            "buildings" to
                d.buildings.map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "latitude" to it.latitude,
                        "longitude" to it.longitude,
                        "floors" to it.floors.map(::floorView),
                        "floorPlansAvailable" to it.approvedPlans().isNotEmpty(),
                    )
                },
            "floorPlansAvailable" to d.buildings.any { it.approvedPlans().isNotEmpty() },
        )
    }

    fun facilities(keyword: String?, buildingId: Long?, floor: String?): Map<String, Any> {
        if (keyword != null && keyword.length > 100)
            fail(HttpStatus.BAD_REQUEST, "검색어는 100자 이하여야 합니다")
        if (floor != null && floor.length > 20) fail(HttpStatus.BAD_REQUEST, "층 이름은 20자 이하여야 합니다")
        val d = registered()
        if (buildingId != null && d.buildings.none { it.id == buildingId })
            fail(HttpStatus.NOT_FOUND, "건물을 찾을 수 없습니다")
        val word = keyword.orEmpty().trim()
        val selectedFloor = floor?.trim()?.takeIf { it.isNotEmpty() }
        val rows =
            publicFacilities().filter {
                (buildingId == null || it.buildingId == buildingId) &&
                    (selectedFloor == null || it.floor.equals(selectedFloor, ignoreCase = true)) &&
                    (word.isEmpty() ||
                        placeMatches(it.name, word) ||
                        placeMatches(it.buildingName, word))
            }
        return mapOf("items" to rows, "total" to rows.size)
    }

    private fun publicFacilities(): List<CampusFacilityDto> =
        data?.buildings.orEmpty().flatMap { b ->
            b.floors
                .filter { it.facilitiesPublic }
                .flatMap { f ->
                    f.facilities.distinct().map { CampusFacilityDto(b.id, b.name, f.floor, it) }
                }
        }

    /**
     * Relevant public reference data only; no dormitory details, notes, URLs or device coordinates.
     */
    fun facilityContext(question: String): String {
        val rows =
            publicFacilities().filter { row ->
                question.contains(row.buildingName, ignoreCase = true) ||
                    row.name.split('/').any { name ->
                        name.length >= 2 && question.contains(name, ignoreCase = true)
                    }
            }
        if (rows.isEmpty()) return ""
        return "\n[학교 배치도 기준 공개 시설 위치]\n" +
            rows.joinToString("\n") { "- ${it.buildingName} ${it.floor}: ${it.name}" } +
            "\n시설 위치는 이 등록 자료를 우선 사용하고, 없는 시설·연결 경로·현재 위치·도면 이미지는 추측하지 마세요."
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
        val g = geometry ?: fail(HttpStatus.SERVICE_UNAVAILABLE, "학교 기준 좌표가 아직 등록되지 않았습니다")
        // 측정 좌표는 이 지역 변수 안에서만 쓰고 저장·로그·대화로 넘기지 않는다.
        val relation = g.relate(GeoPoint(r.latitude, r.longitude))
        val acc = r.horizontalAccuracyMeters
        val status =
            when {
                acc > maxAccuracy -> "LOW_ACCURACY"
                !relation.inside && relation.marginMeters > acc -> "OUTSIDE"
                relation.inside && relation.marginMeters >= acc -> "NEAR_SCHOOL"
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
            d.buildings.any { it.approvedPlans().isNotEmpty() },
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
        // 공개 승인되지 않은 도면은 개수·층·URL을 포함해 아무것도 응답하지 않는다.
        val approved = b.approvedPlans()
        val floors = b.floors.associateBy { it.floor }
        return mapOf(
            "available" to approved.isNotEmpty(),
            "items" to
                approved.map { plan ->
                    val floor = floors[plan.floor]
                    mapOf(
                        "id" to plan.id,
                        "floor" to plan.floor,
                        "label" to plan.label,
                        "url" to plan.url,
                        "facilities" to
                            plan.facilities.ifEmpty {
                                floor?.takeIf { it.facilitiesPublic }?.facilities ?: emptyList()
                            },
                    )
                },
            "reason" to
                when {
                    approved.isNotEmpty() -> "AVAILABLE"
                    b.floorPlans.isNotEmpty() -> "NOT_APPROVED"
                    else -> "NOT_REGISTERED"
                },
        )
    }

    private fun floorView(f: CampusFloor): Map<String, Any?> =
        mapOf(
            "floor" to f.floor,
            "facilitiesPublic" to f.facilitiesPublic,
            "facilities" to if (f.facilitiesPublic) f.facilities else emptyList(),
        )

    private fun schoolView(s: SchoolMap): Map<String, Any?> =
        mapOf(
            "id" to s.id,
            "name" to s.name,
            "latitude" to (boundary?.center?.latitude ?: s.latitude),
            "longitude" to (boundary?.center?.longitude ?: s.longitude),
            "radiusMeters" to boundary?.radiusMeters,
            "boundary" to
                boundary?.let { b ->
                    when (geometry) {
                        is CampusGeometry.Polygon ->
                            mapOf(
                                "type" to "POLYGON",
                                "points" to geometry.vertices,
                                "source" to b.source,
                            )
                        else ->
                            mapOf(
                                "type" to "CIRCLE",
                                "center" to b.center,
                                "radiusMeters" to b.radiusMeters,
                                "source" to b.source,
                            )
                    }
                },
        )

    private fun validCoordinates(lat: Double, lon: Double) =
        CampusGeometry.validCoordinates(lat, lon)
}

@RestController
@RequestMapping("/api")
@Tag(name = "현재 위치")
@SecurityRequirement(name = "bearerAuth")
class CampusController(private val service: CampusService) {
    @GetMapping("/campus/map") fun map() = service.map()

    @GetMapping("/campus/facilities")
    fun facilities(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) buildingId: Long?,
        @RequestParam(required = false) floor: String?,
    ) = service.facilities(keyword, buildingId, floor)

    @PostMapping("/campus/location-check")
    fun check(a: Authentication, @RequestBody r: LocationRequest) = service.check(a.name, r)

    @PostMapping("/chats/{id}/location-check")
    fun chat(a: Authentication, @PathVariable id: Long, @RequestBody r: LocationRequest) =
        service.chat(a.name, id, r)

    @GetMapping("/campus/buildings/{buildingId}/floor-plans")
    fun plans(@PathVariable buildingId: Long) = service.plans(buildingId)
}
