package haengcha.judge

import kotlin.math.floor

/**
 * 옛길 원정 길(조선 대로). 영남대로를 마치면 삼남대로, 이어 관동대로를 걷고, 셋을 다 걸으면 다시 영남대로부터 걷는다.
 * [km]는 옛 리수를 오늘 길에 맞춘 대략의 길이(임시값)다.
 */
enum class Road(val label: String, val km: Int) {
    YEONGNAM("영남대로", 380),
    SAMNAM("삼남대로", 400),
    GWANDONG("관동대로", 360);

    /** 앞선 길을 다 걸었을 때까지의 누적 거리. */
    val startKm: Int get() = entries.take(ordinal).sumOf { it.km }
    val stations: List<Station> get() = Station.entries.filter { it.road == this }
    val destination: Station get() = stations.last()
    val next: Road get() = entries[(ordinal + 1) % entries.size]

    companion object {
        /** 세 길을 한 번씩 다 걸은 거리(한 바퀴). */
        val CYCLE_KM = entries.sumOf { it.km }
    }
}

/** 옛길 원정의 지점. [km]는 그 길의 출발점에서 잰 대략의 누적 거리(임시값)다. 출발점(0km)은 스탬프가 없다. */
enum class Station(val road: Road, val label: String, val kind: String, val km: Int) {
    // 영남대로: 한양 → 동래
    HANYANG(Road.YEONGNAM, "한양 숭례문", "출발", 0),
    PANGYO(Road.YEONGNAM, "판교", "주막", 22),
    YONGIN(Road.YEONGNAM, "용인", "역참", 40),
    YANGJI(Road.YEONGNAM, "양지", "주막", 60),
    JUKSAN(Road.YEONGNAM, "죽산", "역참", 85),
    CHUNGJU(Road.YEONGNAM, "충주", "역참", 130),
    JORYEONG(Road.YEONGNAM, "문경새재 조령관", "관문", 165),
    YUGOK(Road.YEONGNAM, "유곡", "역참", 185),
    NAKDONG(Road.YEONGNAM, "낙동나루", "나루", 210),
    SEONSAN(Road.YEONGNAM, "선산", "주막", 245),
    DAEGU(Road.YEONGNAM, "대구 경상감영", "감영", 285),
    CHEONGDO(Road.YEONGNAM, "청도", "역참", 315),
    MIRYANG(Road.YEONGNAM, "밀양 영남루", "누각", 345),
    YANGSAN(Road.YEONGNAM, "양산", "역참", 365),
    DONGNAE(Road.YEONGNAM, "동래", "도착", 380),

    // 삼남대로: 한양 → 해남(제주로 건너가는 바닷길 앞)
    SAMNAM_GATE(Road.SAMNAM, "한양 숭례문", "출발", 0),
    NAMTAERYEONG(Road.SAMNAM, "남태령", "고개", 15),
    SUWON(Road.SAMNAM, "수원 화성행궁", "행궁", 38),
    JINWI(Road.SAMNAM, "진위", "역참", 62),
    CHEONAN(Road.SAMNAM, "천안삼거리", "주막", 88),
    GONGJU(Road.SAMNAM, "공주 금강나루", "나루", 128),
    EUNJIN(Road.SAMNAM, "은진", "역참", 165),
    SAMNYE(Road.SAMNAM, "삼례", "역참", 205),
    JEONJU(Road.SAMNAM, "전주 전라감영", "감영", 222),
    JEONGEUP(Road.SAMNAM, "정읍", "주막", 262),
    GALJAE(Road.SAMNAM, "장성 갈재", "고개", 288),
    NAJU(Road.SAMNAM, "나주 금성관", "객사", 330),
    YEONGAM(Road.SAMNAM, "영암", "역참", 365),
    HAENAM(Road.SAMNAM, "해남", "도착", 400),

    // 관동대로: 한양 → 평해(관동팔경을 따라)
    HEUNGINJIMUN(Road.GWANDONG, "한양 흥인지문", "출발", 0),
    MANGU(Road.GWANDONG, "망우리 고개", "고개", 12),
    PYEONGGU(Road.GWANDONG, "평구역", "역참", 25),
    YANGGEUN(Road.GWANDONG, "양근나루", "나루", 48),
    WONJU(Road.GWANDONG, "원주 강원감영", "감영", 105),
    ANHEUNG(Road.GWANDONG, "안흥", "주막", 135),
    JINBU(Road.GWANDONG, "진부", "역참", 180),
    DAEGWALLYEONG(Road.GWANDONG, "대관령", "고개", 205),
    GYEONGPODAE(Road.GWANDONG, "강릉 경포대", "누각", 225),
    JUKSEORU(Road.GWANDONG, "삼척 죽서루", "누각", 285),
    ULJIN(Road.GWANDONG, "울진", "역참", 330),
    PYEONGHAE(Road.GWANDONG, "평해 월송정", "도착", 360);

    /** 원정 처음(영남대로 한양)에서 잰 누적 거리. */
    val totalKm: Int get() = road.startKm + km

    /** 같은 길의 다음 지점. 도착 지점이면 없다(다음 길로 넘어간다). */
    val next: Station? get() = road.stations.firstOrNull { it.km > km }
}

/**
 * 원정 진행. [lap]은 세 길을 다 걸은 횟수, [road]는 지금 걷는 길, [roadKm]는 그 길에서 온 거리,
 * [reached]는 마지막으로 지난 지점, [next]는 다음 지점.
 */
data class JourneyStatus(
    val totalKm: Double,
    val lap: Int,
    val road: Road,
    val roadKm: Double,
    val reached: Station,
    val next: Station,
    val kmToNext: Double,
) {
    val progress: Float get() = (roadKm / road.km).toFloat()

    /** 지금까지 찍은 지점 스탬프 수(바퀴를 거듭하면 다시 센다). */
    val stamps: Int get() = stationsReached(0.0, totalKm).size

    /** [r]을 다 걸었는지(이번 바퀴 기준, 한 바퀴를 넘겼으면 모두). */
    fun done(r: Road) = lap > 0 || r.ordinal < road.ordinal
}

/**
 * 옛길 원정(기획서 4.5 확장). 쌓은 주행 거리로 조선 대로를 따라가며 역참·주막·관문에 스탬프를 찍는다.
 * 출퇴근만 해도 조금씩 나아가고, 한 길의 끝에 닿으면 다음 길을 한양에서 다시 떠난다.
 */
fun journey(totalKm: Double): JourneyStatus {
    val lap = floor(totalKm / Road.CYCLE_KM).toInt()
    val cycleKm = totalKm - lap * Road.CYCLE_KM
    val road = Road.entries.last { it.startKm <= cycleKm }
    val roadKm = cycleKm - road.startKm
    val reached = road.stations.last { it.km <= roadKm }
    val next = road.stations.first { it.km > roadKm }
    return JourneyStatus(totalKm, lap, road, roadKm, reached, next, next.km - roadKm)
}

/** 누적 거리가 [beforeKm]에서 [afterKm]로 느는 동안 새로 닿은 지점(길·바퀴를 넘으면 다음 길 지점까지). 출발점은 세지 않는다. */
fun stationsReached(beforeKm: Double, afterKm: Double): List<Station> {
    if (afterKm <= beforeKm) return emptyList()
    val from = floor(beforeKm / Road.CYCLE_KM).toInt()
    val to = floor(afterKm / Road.CYCLE_KM).toInt()
    return (from..to).flatMap { lap ->
        Station.entries.filter { it.km > 0 }.filter { s ->
            val at = lap.toDouble() * Road.CYCLE_KM + s.totalKm
            at > beforeKm && at <= afterKm
        }
    }
}

/**
 * 원정·곳간에 쓰는 주행 거리: 적산거리로 잰 값과 차속으로 잰 값 중 큰 값.
 * 적산거리는 단위가 거칠거나(에뮬레이터는 Virtual Console로 바꿀 때만 늘어남) 못 읽을 수 있어서다.
 */
val TripRecord.journeyKm: Double get() = maxOf(odoKm ?: 0.0, distanceKm)
