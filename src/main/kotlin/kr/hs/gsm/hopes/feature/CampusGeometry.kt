package kr.hs.gsm.hopes.feature

import kotlin.math.*

/** 측정 위치와 학교 경계의 관계. margin은 측정 위치에서 가장 가까운 경계선까지의 거리(m)다. */
internal data class BoundaryRelation(val inside: Boolean, val marginMeters: Double)

/**
 * 원형·다각형 학교 경계 비교. 다각형은 측정 위치를 원점으로 하는 국소 평면(equirectangular)에 투영해 ray casting으로
 * 안팎을 판정하고, 변까지의 최단 거리로 GPS 오차 범위와 경계가 겹치는지 판단한다. 학교 규모(수백 m)에서는 투영 오차가
 * GPS 오차보다 훨씬 작다.
 */
internal sealed interface CampusGeometry {
    fun relate(point: GeoPoint): BoundaryRelation

    data class Circle(val center: GeoPoint, val radiusMeters: Double) : CampusGeometry {
        override fun relate(point: GeoPoint): BoundaryRelation {
            val d = haversine(point, center)
            return BoundaryRelation(d <= radiusMeters, abs(radiusMeters - d))
        }
    }

    data class Polygon(val vertices: List<GeoPoint>) : CampusGeometry {
        override fun relate(point: GeoPoint): BoundaryRelation {
            val xy = vertices.map { project(it, point) }
            var inside = false
            var margin = Double.POSITIVE_INFINITY
            for (i in xy.indices) {
                val a = xy[i]
                val b = xy[(i + 1) % xy.size]
                if ((a.second > 0) != (b.second > 0)) {
                    val x = a.first + (0 - a.second) * (b.first - a.first) / (b.second - a.second)
                    if (x > 0) inside = !inside
                }
                margin = min(margin, segmentDistance(a, b))
            }
            return BoundaryRelation(inside, margin)
        }
    }

    companion object {
        private const val EARTH_RADIUS = 6371000.0

        fun validCoordinates(lat: Double, lon: Double) =
            lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

        /** 등록 자료를 검증해 비교용 경계로 변환한다. 잘못된 자료는 서버 시작 시 거부한다. */
        fun from(boundary: CampusBoundary): CampusGeometry {
            require(!boundary.source.isNullOrBlank()) { "학교 경계 좌표의 출처(source)가 필요합니다" }
            return when (boundary.type.uppercase()) {
                "CIRCLE" -> {
                    val c = requireNotNull(boundary.center) { "CIRCLE 경계에는 center가 필요합니다" }
                    val r = requireNotNull(boundary.radiusMeters) { "CIRCLE 경계에는 radiusMeters가 필요합니다" }
                    require(validCoordinates(c.latitude, c.longitude)) { "잘못된 경계 중심 좌표입니다" }
                    require(r.isFinite() && r > 0) { "radiusMeters는 0보다 커야 합니다" }
                    require(boundary.points.isEmpty()) { "CIRCLE 경계에는 points를 쓸 수 없습니다" }
                    Circle(c, r)
                }
                "POLYGON" -> {
                    require(boundary.center == null && boundary.radiusMeters == null) {
                        "POLYGON 경계에는 center/radiusMeters를 쓸 수 없습니다"
                    }
                    val pts = boundary.points.let { if (it.size > 1 && it.first() == it.last()) it.dropLast(1) else it }
                    require(pts.size >= 3) { "POLYGON 경계에는 서로 다른 꼭짓점 3개 이상이 필요합니다" }
                    require(pts.all { validCoordinates(it.latitude, it.longitude) }) { "잘못된 경계 꼭짓점 좌표입니다" }
                    require(pts.distinct().size == pts.size) { "POLYGON 꼭짓점이 중복됩니다" }
                    val origin = pts.first()
                    val xy = pts.map { project(it, origin) }
                    val area = xy.indices.sumOf { i ->
                        val a = xy[i]
                        val b = xy[(i + 1) % xy.size]
                        a.first * b.second - b.first * a.second
                    } / 2
                    require(abs(area) >= 1.0) { "POLYGON 경계의 면적이 0입니다" }
                    require(!selfIntersects(xy)) { "POLYGON 경계의 변이 서로 교차합니다" }
                    Polygon(pts)
                }
                else -> throw IllegalArgumentException("지원하지 않는 경계 type: ${boundary.type}")
            }
        }

        fun haversine(a: GeoPoint, b: GeoPoint): Double {
            val dLat = Math.toRadians(b.latitude - a.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h =
                sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
            return EARTH_RADIUS * 2 * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1 - h).coerceIn(0.0, 1.0)))
        }

        /** origin을 (0,0)으로 하는 국소 평면 좌표(m). x=동쪽, y=북쪽. */
        private fun project(p: GeoPoint, origin: GeoPoint): Pair<Double, Double> {
            val k = Math.toRadians(1.0) * EARTH_RADIUS
            return Pair(
                (p.longitude - origin.longitude) * k * cos(Math.toRadians(origin.latitude)),
                (p.latitude - origin.latitude) * k,
            )
        }

        /** 원점에서 선분 ab까지의 최단 거리. */
        private fun segmentDistance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val dx = b.first - a.first
            val dy = b.second - a.second
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-a.first * dx - a.second * dy) / len2).coerceIn(0.0, 1.0)
            return hypot(a.first + t * dx, a.second + t * dy)
        }

        private fun selfIntersects(xy: List<Pair<Double, Double>>): Boolean {
            val n = xy.size
            fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>) =
                (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
            for (i in 0 until n) for (j in i + 1 until n) {
                if (j == i + 1 || (i == 0 && j == n - 1)) continue
                val (p1, p2) = xy[i] to xy[(i + 1) % n]
                val (q1, q2) = xy[j] to xy[(j + 1) % n]
                val d1 = cross(q1, q2, p1)
                val d2 = cross(q1, q2, p2)
                val d3 = cross(p1, p2, q1)
                val d4 = cross(p1, p2, q2)
                if (d1 == 0.0 && d2 == 0.0 && d3 == 0.0 && d4 == 0.0) {
                    // 같은 직선 위의 두 변: 구간이 겹칠 때만 교차로 본다.
                    val overlapX = max(min(p1.first, p2.first), min(q1.first, q2.first)) <=
                        min(max(p1.first, p2.first), max(q1.first, q2.first))
                    val overlapY = max(min(p1.second, p2.second), min(q1.second, q2.second)) <=
                        min(max(p1.second, p2.second), max(q1.second, q2.second))
                    if (overlapX && overlapY) return true
                } else if (d1 * d2 <= 0 && d3 * d4 <= 0) return true
            }
            return false
        }
    }
}
