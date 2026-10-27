package haengcha.judge

import kotlin.math.floor

/**
 * 옛길 원정의 지점(영남대로, 한양 → 동래). 영남대로 960리를 [Station.TOTAL_KM]로 잡고,
 * 각 지점의 [km]는 한양에서 잰 대략의 누적 거리(임시값)다.
 */
enum class Station(val label: String, val kind: String, val km: Int) {
    HANYANG("한양 숭례문", "출발", 0),
    PANGYO("판교", "주막", 22),
    YONGIN("용인", "역참", 40),
    YANGJI("양지", "주막", 60),
    JUKSAN("죽산", "역참", 85),
    CHUNGJU("충주", "역참", 130),
    JORYEONG("문경새재 조령관", "관문", 165),
    YUGOK("유곡", "역참", 185),
    NAKDONG("낙동나루", "나루", 210),
    SEONSAN("선산", "주막", 245),
    DAEGU("대구 경상감영", "감영", 285),
    CHEONGDO("청도", "역참", 315),
    MIRYANG("밀양 영남루", "누각", 345),
    YANGSAN("양산", "역참", 365),
    DONGNAE("동래", "도착", 380);

    companion object {
        const val TOTAL_KM = 380
    }
}

/** 원정 진행. [lap]은 마친 바퀴 수, [lapKm]은 이번 바퀴에서 온 거리, [reached]는 마지막으로 지난 지점. */
data class JourneyStatus(val totalKm: Double, val lap: Int, val lapKm: Double, val reached: Station, val next: Station?, val kmToNext: Double) {
    val progress: Float get() = (lapKm / Station.TOTAL_KM).toFloat()
}

/**
 * 옛길 원정(기획서 4.5 확장). 쌓은 주행 거리로 영남대로를 따라가며 역참·주막·관문에 스탬프를 찍는다.
 * 출퇴근만 해도 조금씩 나아가고, 동래에 닿으면 한 바퀴를 마치고 한양에서 다시 떠난다.
 */
fun journey(totalKm: Double): JourneyStatus {
    val lap = floor(totalKm / Station.TOTAL_KM).toInt()
    val lapKm = totalKm - lap * Station.TOTAL_KM
    val reached = Station.entries.last { it.km <= lapKm }
    val next = Station.entries.firstOrNull { it.km > lapKm }
    return JourneyStatus(totalKm, lap, lapKm, reached, next, (next?.km ?: Station.TOTAL_KM) - lapKm)
}

/** 누적 거리가 [beforeKm]에서 [afterKm]로 느는 동안 새로 닿은 지점(바퀴를 넘으면 다음 바퀴 지점까지). 한양 출발은 세지 않는다. */
fun stationsReached(beforeKm: Double, afterKm: Double): List<Station> {
    if (afterKm <= beforeKm) return emptyList()
    val from = floor(beforeKm / Station.TOTAL_KM).toInt()
    val to = floor(afterKm / Station.TOTAL_KM).toInt()
    return (from..to).flatMap { lap ->
        Station.entries.filter { it != Station.HANYANG }.filter { s ->
            val at = lap.toDouble() * Station.TOTAL_KM + s.km
            at > beforeKm && at <= afterKm
        }
    }
}

/**
 * 원정·곳간에 쓰는 주행 거리: 적산거리로 잰 값과 차속으로 잰 값 중 큰 값.
 * 적산거리는 단위가 거칠거나(에뮬레이터는 Virtual Console로 바꿀 때만 늘어남) 못 읽을 수 있어서다.
 */
val TripRecord.journeyKm: Double get() = maxOf(odoKm ?: 0.0, distanceKm)
