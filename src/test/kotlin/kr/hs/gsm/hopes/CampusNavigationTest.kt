package kr.hs.gsm.hopes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kr.hs.gsm.hopes.api.ApiException
import kr.hs.gsm.hopes.feature.CampusData
import kr.hs.gsm.hopes.feature.CampusNavigator
import kr.hs.gsm.hopes.feature.NavigationData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/** ops/campus 의 실제 길찾기 자료로 그래프와 경로 계산을 확인한다. */
class CampusNavigationTest {
    private val mapper = jacksonObjectMapper()
    private val campus: CampusData = mapper.readValue(Files.readString(Path.of("ops/campus/gsm-campus.json")))
    private val navigation: NavigationData =
        mapper.readValue(Files.readString(Path.of("ops/campus/gsm-navigation.json")))

    /** 테스트에서만 모든 도면을 공개 승인된 것으로 바꾼다. 실제 자료 파일은 승인 전 상태 그대로다. */
    private fun approvedCampus() =
        campus.copy(
            buildings =
                campus.buildings.map { b ->
                    b.copy(floorPlans = b.floorPlans.map { it.copy(publicationApproved = true, url = "https://example.test/${it.id}.png") })
                }
        )

    private val navigator by lazy { CampusNavigator(navigation, approvedCampus()) }

    private fun id(map: String, name: String): String {
        @Suppress("UNCHECKED_CAST")
        val items = navigator.places(name)["items"] as List<Map<String, Any?>>
        return items.single { it["floorPlanId"] == map && it["name"] == name }["id"] as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun legs(route: Map<String, Any?>) = route["legs"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun floorPlanId(leg: Map<String, Any?>) = (leg["floorPlan"] as Map<String, Any?>)["id"]

    private fun dump(name: String, route: Map<String, Any?>) {
        val dir = Path.of("target/navigation-samples")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$name.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(route))
    }

    @Test
    fun `real navigation data loads and every place is reachable`() {
        @Suppress("UNCHECKED_CAST")
        val items = navigator.places(null)["items"] as List<Map<String, Any?>>
        assertTrue(items.size > 140)
        assertTrue(setOf("donghaeng-3F", "donghaeng-4F", "donghaeng-5F").all { floor -> items.any { it["floorPlanId"] == floor } })
        val from = id("main-3F", "2학년 1반")
        items.filter { it["id"] != from }.forEach { target ->
            val route = navigator.route(from, target["id"] as String, false)
            assertTrue((route["distanceMeters"] as Int) >= 0, "${target["name"]}")
        }
        // 도면별로 계단 하나에서 같은 층 모든 장소까지의 경로를 남겨 두면 도면 위에 그려 눈으로 확인할 수 있다.
        items.groupBy { it["floorPlanId"] }.forEach { (map, onFloor) ->
            val hub = onFloor.first { it["type"] == "STAIR" }["id"] as String
            val routes = onFloor.filter { it["id"] != hub }.map { navigator.route(hub, it["id"] as String, false) }
            dump("floor-$map", mapOf("routes" to routes))
        }
    }

    @Test
    fun `stairs route shows only the start and destination floor`() {
        val route = navigator.route(id("main-3F", "2학년 1반"), id("main-1F", "교무실"), false)
        dump("main-3F-class-to-main-1F-office", route)
        val legs = legs(route)
        assertEquals(listOf("main-3F", "main-1F"), legs.map(::floorPlanId))
        assertEquals(mapOf("type" to "STAIR", "toFloor" to "1F"), legs[0]["next"])
        assertNull(legs[1]["next"])
        assertEquals(2, route["floorsMoved"])
        assertEquals("3F 2학년 1반에서 출발", (route["steps"] as List<*>).first())
        assertEquals("1F 교무실 도착", (route["steps"] as List<*>).last())
        legs.forEach { assertTrue((it["path"] as List<*>).size >= 2) }
    }

    @Test
    fun `elevator only route uses the elevator or fails clearly`() {
        val route = navigator.route(id("main-4F", "1학년 1반"), id("main-1F", "교장실"), true)
        dump("main-4F-elevator-to-main-1F-principal", route)
        assertEquals("ELEVATOR", (legs(route)[0]["next"] as Map<*, *>)["type"])
        // 금봉관에는 엘리베이터가 없다.
        val e =
            assertThrows(ApiException::class.java) {
                navigator.route(id("main-1F", "교무실"), id("geumbong-3F", "도서실"), true)
            }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status)
    }

    @Test
    fun `route between buildings goes through an estimated outdoor leg`() {
        val route = navigator.route(id("main-2F", "3학년 1반"), id("geumbong-3F", "도서실"), false)
        dump("main-2F-class-to-geumbong-3F-library", route)
        val legs = legs(route)
        // 본관 2F → 계단 → 본관 1F → 실외 → 금봉관 1F → 계단 → 금봉관 3F
        assertEquals(listOf("INDOOR", "INDOOR", "OUTDOOR", "INDOOR", "INDOOR"), legs.map { it["type"] })
        assertEquals(
            listOf("main-2F", "main-1F", "geumbong-1F", "geumbong-3F"),
            legs.filter { it["type"] == "INDOOR" }.map(::floorPlanId),
        )
        assertEquals(mapOf("type" to "OUTDOOR", "toGroup" to "금봉관"), legs[1]["next"])
        assertEquals(true, route["outdoorEstimated"])
        assertEquals(true, legs[2]["estimated"])
    }

    @Test
    fun `tapped point on the floor plan snaps to the nearest corridor`() {
        val route = navigator.route("main-2F@200,110", id("main-2F", "Online Study Cafe"), false)
        dump("main-2F-point-to-cafe", route)
        val legs = legs(route)
        assertEquals(1, legs.size)
        @Suppress("UNCHECKED_CAST")
        val path = legs[0]["path"] as List<Map<String, Double>>
        assertEquals(mapOf("x" to 200.0, "y" to 110.0), path.first())
        assertEquals(105.0, path[1]["y"])
        assertEquals("2F 현재 위치에서 출발", (route["steps"] as List<*>).first())
    }

    @Test
    fun `bad requests are rejected and unapproved floor plans stay hidden`() {
        fun status(block: () -> Unit) = assertThrows(ApiException::class.java) { block() }.status
        val office = id("main-1F", "교무실")
        assertEquals(HttpStatus.BAD_REQUEST, status { navigator.route(office, office, false) })
        assertEquals(HttpStatus.NOT_FOUND, status { navigator.route("nope", office, false) })
        assertEquals(HttpStatus.BAD_REQUEST, status { navigator.route("main-1F@9999,1", office, false) })
        assertEquals(HttpStatus.NOT_FOUND, status { navigator.route("donghaeng-6F@10,10", office, false) })

        // 실제 자료(공개 승인 전)로는 장소도 경로도 나오지 않는다.
        val hidden = campus.copy(buildings = campus.buildings.map { b -> b.copy(floorPlans = b.floorPlans.map { it.copy(publicationApproved = false) }) })
        val unapproved = CampusNavigator(navigation, hidden)
        assertEquals(false, unapproved.places(null)["available"])
        assertEquals(emptyList<Any>(), unapproved.places(null)["items"])
        assertEquals(HttpStatus.NOT_FOUND, status { unapproved.route("main-3F-01", "main-1F-13", false) })
    }

    @Test
    fun `invalid navigation data is rejected at startup`() {
        fun broken(change: (NavigationData) -> NavigationData) =
            assertThrows(IllegalArgumentException::class.java) { CampusNavigator(change(navigation), approvedCampus()) }
        val first = navigation.maps.first()
        // 시설 목록에 없는 이름
        broken { d -> d.copy(maps = listOf(first.copy(places = first.places.map { if (it.type == "ROOM") it.copy(name = "없는 방") else it }))) }
        // 비스듬한 복도
        broken { d -> d.copy(maps = listOf(first.copy(corridors = listOf(listOf(listOf(10.0, 10.0), listOf(20.0, 30.0)))))) }
        // 공개 여부가 다시 철회된 층에 장소를 등록한 채 시작하면 거부한다.
        val privateCampus = approvedCampus().copy(buildings = approvedCampus().buildings.map { b ->
            if (b.id != 4L) b else b.copy(floors = b.floors.map { f -> if (f.floor == "3F") f.copy(facilitiesPublic = false) else f })
        })
        assertThrows(IllegalArgumentException::class.java) { CampusNavigator(navigation, privateCampus) }
        // 복도와 이어지지 않는 층(계단 link 없음)
        broken { d ->
            d.copy(
                maps =
                    d.maps.map { m ->
                        if (m.id == "geumbong-4F") m.copy(places = m.places.filter { it.type != "STAIR" }) else m
                    }
            )
        }
    }
}
