package kr.hs.gsm.hopes.feature

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// ---------------------------------------------------------------- 자료 형식
// (ops/campus/gsm-navigation.json)

/**
 * 도면 위의 장소 하나. 좌표는 모두 도면 이미지 원본 픽셀 기준이다.
 * - rect: [x, y, width, height] 방 영역
 * - door: 문 위치. 없으면 복도 중심선에 가장 가까운 방 테두리를 문으로 본다.
 * - link: 계단·엘리베이터를 층끼리 잇는 이름. 같은 건물 묶음(group)에서 같은 link는 위아래 층과 연결된다.
 */
data class NavPlace(
    val id: String,
    val name: String,
    val buildingId: Long,
    val type: String = "ROOM",
    val rect: List<Double>,
    val door: List<Double>? = null,
    val link: String? = null,
    val note: String? = null,
)

/**
 * 도면 이미지 한 장. id는 gsm-campus.json의 floorPlans id와 같아야 하고, 그 도면이 공개 승인되어야 응답에 쓰인다. corridors는 복도
 * 중심선(가로·세로 선분으로 된 꺾은선)이다. 사람은 이 선을 따라서만 움직인다고 본다.
 */
data class NavMap(
    val id: String,
    val floor: String,
    val group: String,
    val width: Int,
    val height: Int,
    val metersPerPixel: Double,
    val corridors: List<List<List<Double>>>,
    val places: List<NavPlace>,
)

/** 건물 묶음 사이 실외 이동. 실외 지도는 없어서 거리만 쓰고 응답에 추정값임을 표시한다. */
data class NavOutdoor(val distanceMeters: Double, val note: String? = null)

data class NavigationData(
    val source: String? = null,
    val note: String? = null,
    val outdoor: NavOutdoor? = null,
    val maps: List<NavMap> = emptyList(),
)

// ---------------------------------------------------------------- 길찾기 계산 (스프링과 무관한 순수 로직)

/**
 * 도면 자료로 그래프를 만들고 최단 경로를 계산한다.
 * 1. 복도 중심선을 선분으로 나누고, 각 장소의 문 → 복도 중심선 연결점을 선분 위에 끼워 넣는다.
 * 2. 선분 위 점들이 노드, 점 사이가 간선(가중치 = 픽셀 거리 × metersPerPixel)이다.
 * 3. 같은 link의 계단·엘리베이터를 위아래 층끼리 잇고, 서로 다른 건물 묶음의 출입문끼리 실외 간선으로 잇는다.
 * 4. 다익스트라로 최단 경로를 구한 뒤 도면(층)별 구간(leg)으로 나눠 응답한다.
 */
internal class CampusNavigator(private val data: NavigationData, campus: CampusData) {
    private class Pt(val x: Double, val y: Double)

    private class Node(val map: NavMap, val x: Double, val y: Double)

    /** cost: 경로 선택에 쓰는 값, meters: 실제 걸은 거리(계단·엘리베이터는 0). */
    private class Edge(val to: Int, val cost: Double, val meters: Double, val kind: String)

    /** 그래프에서 복도 중심선의 가장 작은 조각. 사용자가 찍은 위치를 붙일 때 쓴다. */
    private class Piece(val a: Int, val b: Int)

    private class Endpoint(
        val map: NavMap,
        val point: Pt,
        val place: NavPlace?,
        val node: Int?,
        val piece: Piece?,
        val snapped: Pt?,
    )

    private class Leg(
        val map: NavMap,
        val points: MutableList<Pt>,
        val arrivedVertically: Boolean,
    ) {
        var nextType: String? = null
        var nextFloor: String? = null
        var nextGroup: String? = null
    }

    private val buildings = campus.buildings.associateBy { it.id }
    private val plans: Map<String, Pair<CampusBuilding, FloorPlan>> =
        campus.buildings.flatMap { b -> b.floorPlans.map { it.id to (b to it) } }.toMap()
    private val maps = data.maps.associateBy { it.id }
    private val nodes = ArrayList<Node>()
    private val nodeKeys = HashMap<String, Int>()
    private val adj = ArrayList<MutableList<Edge>>()
    private val pieces = HashMap<String, MutableList<Piece>>()
    private val places = LinkedHashMap<String, Pair<NavMap, NavPlace>>()
    private val placeNodes = HashMap<String, Int>()
    private val entranceAt = HashMap<Int, NavPlace>()

    /** 공개 승인 + URL이 있는 도면만 길찾기에 쓴다. 승인 전 도면의 장소·복도는 응답에도, 경로 계산에도 나오지 않는다. */
    private val approved: Set<String> =
        data.maps
            .map { it.id }
            .filter { id -> plans[id]?.let { (b, p) -> b.approved(p) && p.url != null } == true }
            .toSet()

    init {
        require(data.maps.isNotEmpty()) { "길찾기 도면(maps)이 없습니다" }
        require(maps.size == data.maps.size) { "길찾기 도면 id가 중복됩니다" }
        data.outdoor?.let {
            require(it.distanceMeters > 0) { "outdoor.distanceMeters는 0보다 커야 합니다" }
        }
        data.maps.forEach(::validate)
        data.maps.forEach(::buildFloor)
        linkFloors()
        linkOutdoor()
        checkConnected()
    }

    // ------------------------------------------------------------ 검증

    private fun validate(m: NavMap) {
        val (_, plan) =
            plans[m.id] ?: error("길찾기 도면 ${m.id}: gsm-campus.json에 같은 id의 floorPlans가 없습니다")
        require(plan.floor == m.floor) {
            "길찾기 도면 ${m.id}: 층(${m.floor})이 도면 자료(${plan.floor})와 다릅니다"
        }
        require(m.width > 0 && m.height > 0) { "길찾기 도면 ${m.id}: width/height를 확인해주세요" }
        require(m.metersPerPixel > 0 && m.metersPerPixel <= 1) {
            "길찾기 도면 ${m.id}: metersPerPixel을 확인해주세요"
        }
        require(m.group.isNotBlank()) { "길찾기 도면 ${m.id}: group이 필요합니다" }
        floorNumber(m.floor)
        require(m.corridors.isNotEmpty()) { "길찾기 도면 ${m.id}: 복도(corridors)가 없습니다" }
        m.corridors.forEach { line ->
            require(line.size >= 2) { "길찾기 도면 ${m.id}: 복도 선은 점이 2개 이상이어야 합니다" }
            line.forEach { inside(m, it, "복도 점") }
            line.zipWithNext().forEach { (a, b) ->
                require((a[0] == b[0]) != (a[1] == b[1])) {
                    "길찾기 도면 ${m.id}: 복도 선분은 가로 또는 세로여야 합니다 ($a → $b)"
                }
            }
        }
        val links = HashSet<String>()
        m.places.forEach { p ->
            require(p.id.matches(Regex("[A-Za-z0-9_-]+"))) { "장소 id ${p.id}: 영문·숫자·-·_만 쓸 수 있습니다" }
            require(places.put(p.id, m to p) == null) { "장소 id ${p.id}가 중복됩니다" }
            require(p.name.isNotBlank()) { "장소 ${p.id}: 이름이 없습니다" }
            require(p.type in TYPES) { "장소 ${p.id}: type은 $TYPES 중 하나여야 합니다" }
            require(p.rect.size == 4 && p.rect[2] > 0 && p.rect[3] > 0) {
                "장소 ${p.id}: rect는 [x,y,width,height]입니다"
            }
            inside(m, listOf(p.rect[0], p.rect[1]), "장소 ${p.id} rect")
            inside(m, listOf(p.rect[0] + p.rect[2], p.rect[1] + p.rect[3]), "장소 ${p.id} rect")
            p.door?.let { inside(m, it, "장소 ${p.id} door") }
            val b = buildings[p.buildingId] ?: error("장소 ${p.id}: 건물 ${p.buildingId}가 없습니다")
            if (b.floors.isNotEmpty()) {
                val floor =
                    b.floors.find { it.floor == m.floor }
                        ?: error("장소 ${p.id}: ${b.name}에 ${m.floor}이 없습니다")
                // 시설 비공개 층(기숙사 등)의 장소는 등록 자체를 막는다.
                require(floor.facilitiesPublic) { "장소 ${p.id}: ${b.name} ${m.floor}은 시설 비공개 층입니다" }
                if (p.type in LISTED_TYPES)
                    require(p.name in floor.facilities) {
                        "장소 ${p.id}: '${p.name}'이 ${b.name} ${m.floor} 시설 목록에 없습니다"
                    }
            }
            if (p.type in VERTICAL_TYPES) {
                require(!p.link.isNullOrBlank()) { "장소 ${p.id}: 계단·엘리베이터에는 link가 필요합니다" }
                require(links.add(p.link)) { "장소 ${p.id}: 한 도면에 같은 link(${p.link})가 두 번 있습니다" }
            } else require(p.link == null) { "장소 ${p.id}: link는 계단·엘리베이터에만 씁니다" }
        }
    }

    private fun inside(m: NavMap, p: List<Double>, what: String) =
        require(
            p.size == 2 && p[0] in 0.0..m.width.toDouble() && p[1] in 0.0..m.height.toDouble()
        ) {
            "길찾기 도면 ${m.id}: $what 좌표가 도면 밖입니다 ($p)"
        }

    // ------------------------------------------------------------ 그래프 만들기

    private fun node(m: NavMap, p: Pt): Int {
        val key = "${m.id}|${(p.x * 10).roundToInt()}|${(p.y * 10).roundToInt()}"
        return nodeKeys.getOrPut(key) {
            nodes += Node(m, p.x, p.y)
            adj += ArrayList<Edge>()
            nodes.size - 1
        }
    }

    private fun connect(a: Int, b: Int, cost: Double, meters: Double, kind: String) {
        if (a == b) return
        adj[a] += Edge(b, cost, meters, kind)
        adj[b] += Edge(a, cost, meters, kind)
    }

    private fun walk(m: NavMap, a: Int, b: Int) {
        val meters = dist(nodes[a].x, nodes[a].y, nodes[b].x, nodes[b].y) * m.metersPerPixel
        connect(a, b, meters, meters, "WALK")
    }

    /** 복도 선분 하나. points에는 선분 위에 놓일 점과 선분 시작점부터의 비율(t)을 모은다. */
    private class Segment(val a: Pt, val b: Pt) {
        val points = mutableListOf(0.0 to a, 1.0 to b)
        val horizontal = a.y == b.y
    }

    private fun buildFloor(m: NavMap) {
        val segments =
            m.corridors.flatMap { line -> line.zipWithNext { a, b -> Segment(pt(a), pt(b)) } }
        // 다른 꺾은선의 꼭짓점이 선분 위에 있으면 교차점으로 끼워 넣는다(T자 복도).
        val vertices = m.corridors.flatten().map(::pt)
        segments.forEach { s ->
            vertices.forEach { v ->
                val (t, q, d) = project(s, v)
                if (d < 1.0) s.points += t to q
            }
        }
        m.places.forEach { p ->
            val (door, onCorridor, segment) = attach(segments, p)
            segment.points += project(segment, onCorridor).first to onCorridor
            val doorNode = node(m, door)
            walk(m, doorNode, node(m, onCorridor))
            placeNodes[p.id] = doorNode
            if (p.type == "ENTRANCE") entranceAt[doorNode] = p
        }
        val floorPieces = pieces.getOrPut(m.id) { mutableListOf() }
        segments.forEach { s ->
            val ordered = s.points.sortedBy { it.first }.map { node(m, it.second) }.distinct()
            ordered.zipWithNext { a, b ->
                walk(m, a, b)
                floorPieces += Piece(a, b)
            }
        }
    }

    /** 문 위치와, 문에서 들어갈 복도 중심선 위 점을 구한다. */
    private fun attach(segments: List<Segment>, p: NavPlace): Triple<Pt, Pt, Segment> {
        p.door?.let { d ->
            val door = pt(d)
            val best = segments.minBy { project(it, door).third }
            return Triple(door, project(best, door).second, best)
        }
        val x0 = p.rect[0]
        val y0 = p.rect[1]
        val x1 = x0 + p.rect[2]
        val y1 = y0 + p.rect[3]
        var best: Triple<Pt, Pt, Segment>? = null
        var bestDistance = Double.MAX_VALUE
        segments.forEach { s ->
            // 선분과 방이 마주 보는 구간이 있으면 그 가운데에 문을 둔다. 없으면 선분 끝에서 가장 가까운 테두리.
            val (lo, hi, fixed) =
                if (s.horizontal) Triple(minOf(s.a.x, s.b.x), maxOf(s.a.x, s.b.x), s.a.y)
                else Triple(minOf(s.a.y, s.b.y), maxOf(s.a.y, s.b.y), s.a.x)
            val (r0, r1) = if (s.horizontal) x0 to x1 else y0 to y1
            val (c0, c1) = if (s.horizontal) y0 to y1 else x0 to x1
            val from = maxOf(lo, r0)
            val to = minOf(hi, r1)
            val along = if (from <= to) (from + to) / 2 else if (r1 < lo) lo else hi
            val across = fixed.coerceIn(c0, c1)
            val alongInRoom = along.coerceIn(r0, r1)
            val door = if (s.horizontal) Pt(alongInRoom, across) else Pt(across, alongInRoom)
            val onCorridor = if (s.horizontal) Pt(along, fixed) else Pt(fixed, along)
            val d = dist(door.x, door.y, onCorridor.x, onCorridor.y)
            if (d < bestDistance - 0.5) {
                bestDistance = d
                best = Triple(door, onCorridor, s)
            }
        }
        return best!!
    }

    private fun linkFloors() {
        data.maps
            .flatMap { m ->
                m.places
                    .filter { it.link != null }
                    .map { Triple(m, it, placeNodes.getValue(it.id)) }
            }
            .groupBy { (m, p) -> m.group to p.link }
            .values
            .forEach { stack ->
                val sorted = stack.sortedBy { floorNumber(it.first.floor) }
                require(sorted.map { it.second.type }.distinct().size == 1) {
                    "link ${sorted.first().second.link}: 계단과 엘리베이터를 같은 link로 묶을 수 없습니다"
                }
                sorted.zipWithNext { (lower, place, a), (upper, _, b) ->
                    if (floorNumber(upper.floor) - floorNumber(lower.floor) == 1) {
                        val cost =
                            if (place.type == "STAIR") STAIR_COST_METERS else ELEVATOR_COST_METERS
                        connect(a, b, cost, 0.0, place.type)
                    }
                }
            }
    }

    private fun linkOutdoor() {
        val outdoor = data.outdoor ?: return
        val entrances = entranceAt.keys.toList()
        entrances.forEachIndexed { i, a ->
            entrances.drop(i + 1).forEach { b ->
                if (nodes[a].map.group != nodes[b].map.group)
                    connect(a, b, outdoor.distanceMeters, outdoor.distanceMeters, "OUTDOOR")
            }
        }
    }

    /** 모든 장소가 서로 오갈 수 있는지 시작할 때 확인한다(엘리베이터 전용은 제외). */
    private fun checkConnected() {
        val start = placeNodes.values.first()
        val seen = BooleanArray(nodes.size)
        val queue = ArrayDeque(listOf(start))
        seen[start] = true
        while (queue.isNotEmpty()) adj[queue.removeFirst()].forEach {
            if (!seen[it.to]) {
                seen[it.to] = true
                queue += it.to
            }
        }
        val unreachable = placeNodes.filterValues { !seen[it] }.keys
        require(unreachable.isEmpty()) { "다른 장소와 이어지지 않은 장소가 있습니다: $unreachable" }
    }

    // ------------------------------------------------------------ 조회

    fun places(query: String?): Map<String, Any?> {
        val q = query?.trim()?.lowercase().orEmpty()
        val items =
            places.values
                .filter { (m, p) ->
                    m.id in approved && (q.isEmpty() || placeMatches(p.name, q) || placeMatches(buildings[p.buildingId]?.name.orEmpty(), q))
                }
                .map { (m, p) -> placeView(m, p) }
        return mapOf("available" to approved.isNotEmpty(), "items" to items)
    }

    private fun publishedPlanViews(building: CampusBuilding?, floor: String?): List<Map<String, Any?>> =
        plans.values.filter { (b,p) -> b.approved(p) && (building == null || b.id == building.id) && (floor == null || p.floor == floor) }
            .map { (b,p) ->
                val map = maps[p.id] ?: maps[p.id.replace("lab-", "main-")]
                val resource = org.springframework.core.io.ClassPathResource("campus/floorplans/${p.id.replace("lab-", "main-")}.png")
                val image = if (map == null && resource.exists()) resource.inputStream.use { javax.imageio.ImageIO.read(it) } else null
                mapOf("id" to p.id.replace("lab-", "main-"), "label" to p.label, "url" to p.url,
                    "floor" to p.floor, "buildingId" to b.id, "group" to b.name,
                    "width" to (map?.width ?: image?.width), "height" to (map?.height ?: image?.height))
            }.distinctBy { it["url"] }

    fun chatLocation(question: String, destinationId: String?): Map<String, Any?>? {
        val floor = Regex("([1-9])\\s*(?:층|[fF])").findAll(question).lastOrNull()?.groupValues?.get(1)?.plus("F")
        val normalized = question.replace("기숙사", "동행관")
        val building = buildings.values.filter { normalized.contains(it.name) }.maxByOrNull { normalized.lastIndexOf(it.name) }
        val selected = if (destinationId != null) listOf(places[destinationId]?.takeIf { it.first.id in approved }
            ?: fail(HttpStatus.NOT_FOUND, "공개된 목적지를 찾을 수 없습니다"))
        else places.values.filter { (m,p) -> m.id in approved && (floor == null || m.floor == floor) &&
            (building == null || p.buildingId == building.id) && p.type !in setOf("STAIR", "ELEVATOR", "ENTRANCE") &&
            placeMatches(p.name, question) }
        if (selected.isNotEmpty()) return mapOf(
            "status" to if (selected.size == 1) "DESTINATION_READY" else "SELECT_DESTINATION",
            "candidates" to selected.map { (m,p) -> mapOf("place" to endpointView(endpoint(p.id)), "floorPlan" to floorPlanView(m)) },
            "requiresOrigin" to (selected.size == 1))
        if (destinationId == null && (building != null || question.contains("홈베이스"))) {
            val images = publishedPlanViews(building, floor)
            val buildingFloorOnly = building != null && (floor == null ||
                Regex("(?:본관|실습동|금봉관|동행관)\\s*[1-9]\\s*(?:층|[fF])\\s*(?:위치|어디|어딨|어딘|도면|배치도|평면도|지도|은|는|없어|있어|있나|안내|알려|\\?|$)").containsMatchIn(normalized))
            val scope = listOfNotNull(building?.name, floor?.replace("F", "층")).joinToString(" ")
            val message = if (buildingFloorOnly && images.isNotEmpty()) "$scope 도면이야. 찾는 장소나 출발 위치를 선택해줘."
                else "$scope 배치도에서 요청한 장소의 정확한 표기와 좌표를 확인하지 못했어. 표기가 없다고 실제 시설이 없다는 뜻은 아니야. 확인되지 않은 위치는 추측하지 않을게."
            return mapOf("status" to if (buildingFloorOnly && images.isNotEmpty()) "FLOOR_OVERVIEW" else "LOCATION_UNVERIFIED",
                "requiresOrigin" to false, "candidates" to emptyList<Any>(), "floorPlans" to images, "message" to message)
        }
        return null
    }

    fun route(from: String, to: String, elevatorOnly: Boolean): Map<String, Any?> {
        if (from.trim() == to.trim()) fail(HttpStatus.BAD_REQUEST, "출발지와 도착지가 같습니다")
        val start = endpoint(from)
        val end = endpoint(to)

        // 사용자가 찍은 위치는 요청마다 임시 노드로 붙인다(기본 그래프는 바꾸지 않는다).
        val base = nodes.size
        val temp = ArrayList<Pt>()
        val tempMaps = ArrayList<NavMap>()
        val extra = HashMap<Int, MutableList<Edge>>()
        fun tempNode(m: NavMap, p: Pt): Int {
            temp += p
            tempMaps += m
            return base + temp.size - 1
        }
        fun at(i: Int) = if (i < base) Pt(nodes[i].x, nodes[i].y) else temp[i - base]
        fun mapAt(i: Int) = if (i < base) nodes[i].map else tempMaps[i - base]
        fun link(a: Int, b: Int, m: NavMap) {
            val meters = dist(at(a).x, at(a).y, at(b).x, at(b).y) * m.metersPerPixel
            extra.getOrPut(a) { mutableListOf() } += Edge(b, meters, meters, "WALK")
            extra.getOrPut(b) { mutableListOf() } += Edge(a, meters, meters, "WALK")
        }
        fun attachPoint(e: Endpoint): Pair<Int, Int?> {
            if (e.node != null) return e.node to null
            val p = tempNode(e.map, e.point)
            val q = tempNode(e.map, e.snapped!!)
            link(p, q, e.map)
            link(q, e.piece!!.a, e.map)
            link(q, e.piece.b, e.map)
            return p to q
        }
        val (source, sourceSnap) = attachPoint(start)
        val (target, targetSnap) = attachPoint(end)
        // 두 위치가 복도의 같은 조각 위에 있으면 조각 끝까지 돌아가지 않고 바로 잇는다.
        if (sourceSnap != null && targetSnap != null && start.map == end.map) {
            val a = start.piece!!
            val b = end.piece!!
            if ((a.a == b.a && a.b == b.b) || (a.a == b.b && a.b == b.a))
                link(sourceSnap, targetSnap, start.map)
        }

        val total = base + temp.size
        val cost = DoubleArray(total) { Double.MAX_VALUE }
        val prev = IntArray(total) { -1 }
        val prevEdge = arrayOfNulls<Edge>(total)
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        cost[source] = 0.0
        queue += 0.0 to source
        while (queue.isNotEmpty()) {
            val (c, u) = queue.poll()
            if (c > cost[u]) continue
            if (u == target) break
            val edges = (if (u < base) adj[u] else emptyList()) + extra[u].orEmpty()
            for (e in edges) {
                if (elevatorOnly && e.kind == "STAIR") continue
                if (mapAt(e.to).id !in approved) continue
                val next = c + e.cost
                if (next < cost[e.to]) {
                    cost[e.to] = next
                    prev[e.to] = u
                    prevEdge[e.to] = e
                    queue += next to e.to
                }
            }
        }
        if (cost[target] == Double.MAX_VALUE)
            fail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                if (elevatorOnly) "엘리베이터만으로 갈 수 있는 경로가 없습니다" else "경로를 찾을 수 없습니다",
            )

        val sequence =
            generateSequence(target) { prev[it].takeIf { p -> p >= 0 } }.toList().reversed()
        val hops = sequence.drop(1).map { prevEdge[it]!! }

        val legs = mutableListOf<Any>()
        val indoor = mutableListOf<Leg>()
        var current = Leg(mapAt(source), mutableListOf(at(source)), false)
        var meters = 0.0
        var floorsMoved = 0
        var seconds = 0.0
        var rides = 0
        var outdoor = false
        hops.forEachIndexed { i, e ->
            val here = sequence[i]
            val there = sequence[i + 1]
            meters += e.meters
            when (e.kind) {
                "WALK" -> current.points += at(there)
                "STAIR",
                "ELEVATOR" -> {
                    val moved =
                        abs(floorNumber(mapAt(there).floor) - floorNumber(mapAt(here).floor))
                    floorsMoved += moved
                    seconds +=
                        if (e.kind == "STAIR") STAIR_SECONDS_PER_FLOOR * moved
                        else ELEVATOR_SECONDS_PER_FLOOR * moved
                    if (current.points.size == 1 && current.arrivedVertically) {
                        // 계단으로 여러 층을 지나갈 때 중간 층은 도면을 보여 주지 않는다.
                        indoor.last().nextFloor = mapAt(there).floor
                    } else {
                        if (e.kind == "ELEVATOR") rides++
                        current.nextType = e.kind
                        current.nextFloor = mapAt(there).floor
                        indoor += current
                        legs += current
                    }
                    current = Leg(mapAt(there), mutableListOf(at(there)), true)
                }
                "OUTDOOR" -> {
                    outdoor = true
                    current.nextType = "OUTDOOR"
                    current.nextGroup = mapAt(there).group
                    indoor += current
                    legs += current
                    legs +=
                        outdoorLeg(entranceAt.getValue(here), entranceAt.getValue(there), e.meters)
                    current = Leg(mapAt(there), mutableListOf(at(there)), false)
                }
            }
        }
        indoor += current
        legs += current
        seconds += meters / WALKING_SPEED + rides * ELEVATOR_WAIT_SECONDS

        val legViews = legs.map { if (it is Leg) legView(it) else it }
        return mapOf(
            "from" to endpointView(start),
            "to" to endpointView(end),
            "elevatorOnly" to elevatorOnly,
            "distanceMeters" to meters.roundToInt(),
            "floorsMoved" to floorsMoved,
            "estimatedSeconds" to seconds.roundToInt(),
            "outdoorEstimated" to outdoor,
            "distanceEstimated" to true,
            "geometryEstimated" to true,
            "positionSource" to "USER_SELECTED_FLOORPLAN",
            "legs" to legViews,
            "steps" to steps(start, end, legs),
        )
    }

    // ------------------------------------------------------------ 위치 해석

    /** 장소 id(예: main-3F-01) 또는 도면 위 좌표(예: main-3F@120.5,98). */
    private fun endpoint(spec: String): Endpoint {
        val text = spec.trim()
        val point = POINT.matchEntire(text)
        if (point != null) {
            val m =
                maps[point.groupValues[1]]?.takeIf { it.id in approved }
                    ?: fail(HttpStatus.NOT_FOUND, "도면을 찾을 수 없습니다")
            val p = Pt(point.groupValues[2].toDouble(), point.groupValues[3].toDouble())
            if (p.x !in 0.0..m.width.toDouble() || p.y !in 0.0..m.height.toDouble())
                fail(HttpStatus.BAD_REQUEST, "도면 밖의 좌표입니다")
            val piece = pieces.getValue(m.id).minBy { piece -> projectPiece(piece, p).second }
            val snapped = projectPiece(piece, p).first
            return Endpoint(m, p, null, null, piece, snapped)
        }
        val (m, place) =
            places[text]?.takeIf { it.first.id in approved }
                ?: fail(HttpStatus.NOT_FOUND, "장소를 찾을 수 없습니다")
        val n = placeNodes.getValue(place.id)
        return Endpoint(m, Pt(nodes[n].x, nodes[n].y), place, n, null, null)
    }

    private fun projectPiece(piece: Piece, p: Pt): Pair<Pt, Double> {
        val a = nodes[piece.a]
        val b = nodes[piece.b]
        val (_, q, d) = project(Segment(Pt(a.x, a.y), Pt(b.x, b.y)), p)
        return q to d
    }

    // ------------------------------------------------------------ 응답

    private fun floorPlanView(m: NavMap): Map<String, Any?> {
        val (building, plan) = plans.getValue(m.id)
        return mapOf(
            "id" to m.id,
            "label" to plan.label,
            "url" to plan.url,
            "width" to m.width,
            "height" to m.height,
            "floor" to m.floor,
            "group" to m.group,
            "buildingId" to building.id,
        )
    }

    private fun placeView(m: NavMap, p: NavPlace): Map<String, Any?> =
        mapOf(
            "id" to p.id,
            "name" to p.name,
            "type" to p.type,
            "buildingId" to p.buildingId,
            "buildingName" to buildings[p.buildingId]?.name,
            "floor" to m.floor,
            "floorPlanId" to m.id,
            "note" to p.note,
        )

    private fun endpointView(e: Endpoint): Map<String, Any?> {
        val base =
            e.place?.let { placeView(e.map, it) }
                ?: mapOf("floor" to e.map.floor, "floorPlanId" to e.map.id)
        val area =
            e.place?.rect?.let { (x, y, w, h) ->
                mapOf("x" to x, "y" to y, "width" to w, "height" to h)
            }
        return base + mapOf("x" to round(e.point.x), "y" to round(e.point.y), "area" to area)
    }

    private fun legView(l: Leg): Map<String, Any?> =
        mapOf(
            "type" to "INDOOR",
            "floorPlan" to floorPlanView(l.map),
            "path" to simplify(l.points).map { mapOf("x" to round(it.x), "y" to round(it.y)) },
            "next" to
                when (l.nextType) {
                    null -> null
                    "OUTDOOR" -> mapOf("type" to "OUTDOOR", "toGroup" to l.nextGroup)
                    else -> mapOf("type" to l.nextType, "toFloor" to l.nextFloor)
                },
        )

    private fun outdoorLeg(from: NavPlace, to: NavPlace, meters: Double): Map<String, Any?> =
        mapOf(
            "type" to "OUTDOOR",
            "from" to
                mapOf(
                    "placeId" to from.id,
                    "name" to from.name,
                    "buildingName" to buildings[from.buildingId]?.name,
                ),
            "to" to
                mapOf(
                    "placeId" to to.id,
                    "name" to to.name,
                    "buildingName" to buildings[to.buildingId]?.name,
                ),
            "distanceMeters" to meters.roundToInt(),
            "estimated" to true,
            "note" to data.outdoor?.note,
        )

    private fun steps(start: Endpoint, end: Endpoint, legs: List<Any>): List<String> {
        val out = mutableListOf<String>()
        out +=
            start.place?.let { "${start.map.floor} ${it.name}에서 출발" }
                ?: "${start.map.floor} 현재 위치에서 출발"
        legs.forEach { leg ->
            when {
                leg is Leg && leg.nextType == "STAIR" -> out += "계단으로 ${leg.nextFloor} 이동"
                leg is Leg && leg.nextType == "ELEVATOR" -> out += "엘리베이터로 ${leg.nextFloor} 이동"
                leg is Map<*, *> -> {
                    val f = leg["from"] as Map<*, *>
                    val t = leg["to"] as Map<*, *>
                    out +=
                        "${f["name"]}로 나가 ${t["buildingName"]} ${t["name"]}로 이동(약 ${leg["distanceMeters"]}m, 추정)"
                }
            }
        }
        out +=
            end.place?.let { p ->
                "${end.map.floor} ${p.name} 도착" + (p.note?.let { " ($it)" } ?: "")
            } ?: "${end.map.floor} 선택한 위치 도착"
        return out
    }

    companion object {
        val TYPES = setOf("ROOM", "RESTROOM", "AREA", "ENTRANCE", "STAIR", "ELEVATOR")
        private val LISTED_TYPES = setOf("ROOM", "RESTROOM", "AREA")
        private val VERTICAL_TYPES = setOf("STAIR", "ELEVATOR")
        private val POINT = Regex("""^([A-Za-z0-9_-]+)@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)$""")

        /** 경로 선택용 한 층 이동 비용(미터 환산). 엘리베이터는 기다리는 시간이 있어 조금 더 크게 둔다. */
        const val STAIR_COST_METERS = 18.0
        const val ELEVATOR_COST_METERS = 20.0
        const val WALKING_SPEED = 1.2
        const val STAIR_SECONDS_PER_FLOOR = 15.0
        const val ELEVATOR_SECONDS_PER_FLOOR = 5.0
        const val ELEVATOR_WAIT_SECONDS = 30.0

        fun floorNumber(floor: String): Int {
            Regex("^(\\d+)F$").matchEntire(floor)?.let {
                return it.groupValues[1].toInt()
            }
            Regex("^B(\\d+)$").matchEntire(floor)?.let {
                return -it.groupValues[1].toInt()
            }
            throw IllegalArgumentException("층 이름은 1F, 2F 또는 B1 형식이어야 합니다: $floor")
        }

        private fun pt(p: List<Double>) = Pt(p[0], p[1])

        private fun dist(x1: Double, y1: Double, x2: Double, y2: Double) = hypot(x1 - x2, y1 - y2)

        private fun round(v: Double) = (v * 10).roundToInt() / 10.0

        /** 점 p를 선분 s에 수직으로 내린 점. (비율 t, 내린 점, 거리) */
        private fun project(s: Segment, p: Pt): Triple<Double, Pt, Double> {
            val dx = s.b.x - s.a.x
            val dy = s.b.y - s.a.y
            val length = dx * dx + dy * dy
            val t = (((p.x - s.a.x) * dx + (p.y - s.a.y) * dy) / length).coerceIn(0.0, 1.0)
            val q = Pt(s.a.x + t * dx, s.a.y + t * dy)
            return Triple(t, q, dist(p.x, p.y, q.x, q.y))
        }

        /** 같은 직선 위의 중간 점을 지워 앱이 그릴 점 수를 줄인다. */
        private fun simplify(points: List<Pt>): List<Pt> {
            val out = mutableListOf<Pt>()
            points.forEach { p ->
                if (out.isNotEmpty() && dist(out.last().x, out.last().y, p.x, p.y) < 0.05)
                    return@forEach
                if (out.size >= 2) {
                    val a = out[out.size - 2]
                    val b = out.last()
                    val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
                    val dot = (b.x - a.x) * (p.x - b.x) + (b.y - a.y) * (p.y - b.y)
                    if (abs(cross) < 0.01 && dot >= 0) out.removeAt(out.size - 1)
                }
                out += p
            }
            return out
        }
    }
}

// ---------------------------------------------------------------- 서비스 · API

@Service
class CampusNavigationService(
    mapper: ObjectMapper,
    @Value("\${hopes.campus.navigation-path:}") path: String,
    campus: CampusService,
) {
    private val navigator: CampusNavigator? =
        path
            .takeIf { it.isNotBlank() }
            ?.let { p ->
                val campusData =
                    campus.referenceData()
                        ?: throw IllegalStateException(
                            "CAMPUS_NAVIGATION_PATH를 쓰려면 CAMPUS_DATA_PATH도 필요합니다"
                        )
                val data =
                    Files.newBufferedReader(Path.of(p)).use { mapper.readValue<NavigationData>(it) }
                CampusNavigator(data, campusData)
            }

    private fun ready() = navigator ?: fail(HttpStatus.SERVICE_UNAVAILABLE, "길찾기 자료가 아직 등록되지 않았습니다")

    fun places(query: String?) = ready().places(query)

    fun route(from: String, to: String, elevatorOnly: Boolean) =
        ready().route(from, to, elevatorOnly)

    fun chatLocation(question: String, destinationId: String?) =
        if (destinationId != null) ready().chatLocation(question, destinationId)
        else navigator?.chatLocation(question, null)
}

@RestController
@RequestMapping("/api/campus/navigation")
@Tag(name = "교내 길찾기")
@SecurityRequirement(name = "bearerAuth")
class CampusNavigationController(private val service: CampusNavigationService) {
    /** 길찾기에 쓸 수 있는 장소 목록(공개 승인된 도면만). q로 이름 검색. */
    @GetMapping("/places")
    fun places(@RequestParam(required = false) q: String?) = service.places(q)

    /**
     * from/to: 장소 id(예: main-3F-01) 또는 도면 위 좌표(예: main-3F@120.5,98). 응답의 legs를 순서대로 보여 주면 된다.
     * INDOOR 구간은 floorPlan 이미지 위에 path 좌표를 이어 그린다.
     */
    @GetMapping("/route")
    fun route(
        @RequestParam from: String,
        @RequestParam to: String,
        @RequestParam(defaultValue = "false") elevatorOnly: Boolean,
    ) = service.route(from, to, elevatorOnly)
}
