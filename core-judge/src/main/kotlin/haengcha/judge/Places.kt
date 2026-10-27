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

/** 장면이 없는 도감 지역이 함께 쓰는 단골 등급. */
val TOWN_GRADES = listOf("나그네", "길손", "단골손님", "이웃사촌", "명예 주민")

/**
 * 지역(기획서 4.3). 지역마다 정차 화면 3D 장면과 상징([theme], [emblem]), 단골 등급이 있다.
 * 울산·횡성은 단골 등급마다 장면이 달라지는 지역 테마이고([graded]), 나머지 도감 지역은 장면 하나에 공통 등급 이름을 쓴다.
 * 범위는 시청·군청을 중심으로 한 원으로 잡은 임시값(좌표는 대략값)이다. 행정 경계 데이터는 사업화 단계에서 붙인다.
 * [grades]는 단골 등급 이름(부록 A), 앞에서부터 1단계다.
 */
enum class Region(
    val label: String,
    val theme: String,
    val center: GeoPoint,
    val radiusKm: Double,
    val grades: List<String>,
    val emblem: String,
    val graded: Boolean = false,
) {
    ULSAN("울산", "고래", GeoPoint(35.5384, 129.3114), 20.0, listOf("노말 고래", "동 고래", "은 고래", "금 고래", "플래티넘 고래"), "🐋", graded = true),
    HOENGSEONG("횡성", "한우", GeoPoint(37.4918, 127.9850), 15.0, listOf("3등급 한우", "2등급 한우", "1등급 한우", "1+ 한우", "투뿔(1++) 한우"), "🐂", graded = true),
    SEOUL("서울", "해치", GeoPoint(37.5663, 126.9779), 14.0, TOWN_GRADES, "🦁"),
    BUSAN("부산", "바다", GeoPoint(35.1798, 129.0750), 18.0, TOWN_GRADES, "🌊"),
    DAEGU("대구", "사과", GeoPoint(35.8714, 128.6014), 15.0, TOWN_GRADES, "🍎"),
    INCHEON("인천", "짜장면", GeoPoint(37.4563, 126.7052), 12.0, TOWN_GRADES, "🍜"),
    GWANGJU("광주", "무등산", GeoPoint(35.1601, 126.8514), 15.0, TOWN_GRADES, "⛰️"),
    DAEJEON("대전", "한빛탑", GeoPoint(36.3504, 127.3845), 15.0, TOWN_GRADES, "🗼"),
    GYEONGJU("경주", "왕관", GeoPoint(35.8562, 129.2247), 12.0, TOWN_GRADES, "👑"),
    JEONJU("전주", "비빔밥", GeoPoint(35.8242, 127.1480), 10.0, TOWN_GRADES, "🍚"),
    GANGNEUNG("강릉", "커피", GeoPoint(37.7519, 128.8760), 12.0, TOWN_GRADES, "☕"),
    JEJU("제주", "돌하르방", GeoPoint(33.4996, 126.5312), 15.0, TOWN_GRADES, "🗿"),
    YEOSU("여수", "밤바다", GeoPoint(34.7604, 127.6622), 12.0, TOWN_GRADES, "🌃"),
    ANDONG("안동", "하회탈", GeoPoint(36.5684, 128.7294), 12.0, TOWN_GRADES, "🎭"),
    CHEONGJU("청주", "직지", GeoPoint(36.6424, 127.4890), 12.0, TOWN_GRADES, "📖");

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
