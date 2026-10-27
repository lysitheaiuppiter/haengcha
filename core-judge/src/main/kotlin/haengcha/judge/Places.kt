package haengcha.judge

import java.time.LocalDate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 위도·경도 한 점(도). */
data class GeoPoint(val lat: Double, val lon: Double) {
    /** 대원 거리(km). */
    fun distanceKm(other: GeoPoint): Double {
        val r = 6371.0088
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}

/**
 * 지역 테마(기획서 4.3). 제출 범위는 울산·횡성 두 곳이다.
 * 범위는 시청·군청을 중심으로 한 원으로 잡은 임시값이다. 행정 경계 데이터는 사업화 단계에서 붙인다.
 * [grades]는 단골 등급 이름(부록 A), 앞에서부터 1단계다.
 */
enum class Region(val label: String, val theme: String, val center: GeoPoint, val radiusKm: Double, val grades: List<String>) {
    ULSAN("울산", "고래", GeoPoint(35.5384, 129.3114), 20.0, listOf("노말 고래", "동 고래", "은 고래", "금 고래", "플래티넘 고래")),
    HOENGSEONG("횡성", "한우", GeoPoint(37.4918, 127.9850), 15.0, listOf("3등급 한우", "2등급 한우", "1등급 한우", "1+ 한우", "투뿔(1++) 한우"));

    operator fun contains(p: GeoPoint) = center.distanceKm(p) <= radiusKm

    companion object {
        fun at(p: GeoPoint): Region? = entries.firstOrNull { p in it }
    }
}

/**
 * 단골 등급 규칙(임시값). 그 지역을 지난 주행이 있었던 날 수로 정한다.
 * [daysForGrade]의 n번째 값을 넘기면 n+1단계다(1일이면 1단계, 3일이면 2단계…).
 */
data class RegularRule(val daysForGrade: List<Int> = listOf(1, 3, 7, 15, 30))

/** 한 지역의 단골 기록. [grade]는 0부터 센다(0 = 1단계). */
data class RegularStatus(val region: Region, val visitDays: Int, val lastVisit: LocalDate, val grade: Int, val daysToNext: Int?) {
    val gradeLabel: String get() = region.grades[grade]
}

/** 방문한 지역만, 최근에 간 곳부터. */
fun regulars(records: List<TripRecord>, rule: RegularRule = RegularRule()): List<RegularStatus> =
    Region.entries.mapNotNull { region ->
        val days = records.filter { region in it.regions }.map { it.date }.toSortedSet()
        if (days.isEmpty()) return@mapNotNull null
        val grade = rule.daysForGrade.count { days.size >= it } - 1
        val next = rule.daysForGrade.getOrNull(grade + 1)?.minus(days.size)
        RegularStatus(region, days.size, days.last(), grade.coerceAtMost(region.grades.lastIndex), next)
    }.sortedByDescending { it.lastVisit }
