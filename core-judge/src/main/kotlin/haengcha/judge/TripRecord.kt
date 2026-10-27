package haengcha.judge

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** 주행 보너스 규칙 묶음. */
data class BonusRules(
    val guardian: GuardianRule = GuardianRule(),
    val eco: EcoRule = EcoRule(),
    val weather: WeatherRule = WeatherRule(),
    val rest: RestRule = RestRule(),
    val arrival: ArrivalRule = ArrivalRule(),
)

/**
 * 저장하는 주행 기록 한 줄. 날짜는 주행을 시작한 날(현지 시간)이다.
 * [inspected]는 감찰을 끝까지 한 주행(통과·실패)이고, 그중 통과하면 [mapae]다.
 * [regions]는 이 주행에서 지난 지역, [guardian]은 아이 지킴이 보너스를 받은 어린이보호구역 통과 횟수다.
 * [kmPerKwh]부터는 Vehicle·NaviHelper SDK로 판정한 값이다: 전비와 검약 보너스, 적산거리로 잰 거리, 빗길·눈길,
 * 야행(전조등), 출발할 때 안전띠, 역참 휴식, 도착한 명소.
 * [xp]에는 아이 지킴이·검약·빗길 차분·역참·명소 첫 도착 보너스가 들어 있다.
 */
data class TripRecord(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val date: LocalDate,
    val distanceKm: Double,
    val movingMinutes: Double,
    val xp: Int,
    val harshAccel: Int,
    val harshDecel: Int,
    val safeSegments: Int,
    val calmSegments: Int,
    val inspected: Boolean = false,
    val mapae: Boolean = false,
    val regions: Set<Region> = emptySet(),
    val guardian: Int = 0,
    val kmPerKwh: Double? = null,
    val odoKm: Double? = null,
    val eco: Boolean = false,
    val weather: Weather? = null,
    val weatherCalm: Boolean = false,
    val night: Boolean = false,
    val belt: Boolean? = null,
    val rested: Boolean = false,
    val stamp: Landmark? = null,
) {
    fun toCsv(): String = String.format(
        Locale.ROOT, "%d,%d,%s,%.3f,%.2f,%d,%d,%d,%d,%d,%d,%d,%s,%d,%s,%s,%d,%s,%d,%d,%s,%d,%s",
        startEpochMs, endEpochMs, date, distanceKm, movingMinutes, xp, harshAccel, harshDecel, safeSegments, calmSegments,
        if (inspected) 1 else 0, if (mapae) 1 else 0, regions.joinToString(";") { it.name }, guardian,
        kmPerKwh?.let { String.format(Locale.ROOT, "%.2f", it) }.orEmpty(),
        odoKm?.let { String.format(Locale.ROOT, "%.3f", it) }.orEmpty(),
        if (eco) 1 else 0, weather?.name.orEmpty(), if (weatherCalm) 1 else 0, if (night) 1 else 0,
        when (belt) { true -> "1"; false -> "0"; null -> "" }, if (rested) 1 else 0, stamp?.name.orEmpty(),
    )

    companion object {
        const val CSV_HEADER = "start_epoch_ms,end_epoch_ms,date,distance_km,moving_min,xp," +
            "harsh_accel,harsh_decel,safe_segments,calm_segments,inspected,mapae,regions,guardian," +
            "km_per_kwh,odo_km,eco,weather,weather_calm,night,belt,rested,stamp"

        /**
         * [firstStamp]는 [TripExtras.stamp] 명소에 처음 도착했는지(첫 도착만 보너스).
         * 검약·빗길 차분·역참·명소 보너스를 판정해 경험치에 더한다.
         */
        fun of(
            trip: CompletedTrip,
            startEpochMs: Long,
            endEpochMs: Long,
            zone: ZoneId,
            regions: Set<Region> = emptySet(),
            guardian: Int = 0,
            extras: TripExtras = TripExtras(),
            firstStamp: Boolean = true,
            rules: BonusRules = BonusRules(),
        ): TripRecord {
            val r = trip.result
            val eco = extras.kmPerKwh?.let { it >= rules.eco.targetKmPerKwh } ?: false
            val weather = if (extras.wetSec >= rules.weather.minWetSec) (if (extras.snow) Weather.SNOW else Weather.RAIN) else null
            val weatherCalm = weather != null && r.events.isEmpty()
            val bonus = guardian * rules.guardian.bonusXp +
                (if (eco) rules.eco.bonusXp else 0) +
                (if (weatherCalm) rules.weather.bonusXp else 0) +
                (if (extras.rested) rules.rest.bonusXp else 0) +
                (if (extras.stamp != null && firstStamp) rules.arrival.bonusXp else 0)
            return TripRecord(
                startEpochMs = startEpochMs,
                endEpochMs = endEpochMs,
                date = Instant.ofEpochMilli(startEpochMs).atZone(zone).toLocalDate(),
                distanceKm = r.distanceKm,
                movingMinutes = r.movingMinutes,
                xp = r.xp + bonus,
                harshAccel = r.harshAccel,
                harshDecel = r.harshDecel,
                safeSegments = r.safeSegments,
                calmSegments = r.calmSegments,
                inspected = r.inspection?.outcome.let {
                    it == InspectionOutcome.PASSED || it == InspectionOutcome.FAILED
                },
                mapae = r.mapae,
                regions = regions,
                guardian = guardian,
                kmPerKwh = extras.kmPerKwh,
                odoKm = extras.odoKm,
                eco = eco,
                weather = weather,
                weatherCalm = weatherCalm,
                night = extras.night,
                belt = extras.belt,
                rested = extras.rested,
                stamp = extras.stamp,
            )
        }

        /** 형식이 맞지 않는 줄(머리글 포함)은 null. 감찰 열이 없던 10열, 지역 열이 없던 12열, 차량 신호 열이 없던 14열 기록도 읽는다. */
        fun parse(line: String): TripRecord? {
            val f = line.trim().split(',')
            if (f.size != 10 && f.size != 12 && f.size != 14 && f.size != 23) return null
            return runCatching {
                TripRecord(
                    f[0].toLong(), f[1].toLong(), LocalDate.parse(f[2]), f[3].toDouble(), f[4].toDouble(),
                    f[5].toInt(), f[6].toInt(), f[7].toInt(), f[8].toInt(), f[9].toInt(),
                    inspected = f.getOrNull(10) == "1",
                    mapae = f.getOrNull(11) == "1",
                    regions = f.getOrNull(12).orEmpty().split(';').filter { it.isNotEmpty() }.map(Region::valueOf).toSet(),
                    guardian = f.getOrNull(13)?.toInt() ?: 0,
                    kmPerKwh = f.getOrNull(14)?.toDoubleOrNull(),
                    odoKm = f.getOrNull(15)?.toDoubleOrNull(),
                    eco = f.getOrNull(16) == "1",
                    weather = f.getOrNull(17)?.takeIf { it.isNotEmpty() }?.let(Weather::valueOf),
                    weatherCalm = f.getOrNull(18) == "1",
                    night = f.getOrNull(19) == "1",
                    belt = when (f.getOrNull(20)) { "1" -> true; "0" -> false; else -> null },
                    rested = f.getOrNull(21) == "1",
                    stamp = f.getOrNull(22)?.takeIf { it.isNotEmpty() }?.let(Landmark::valueOf),
                )
            }.getOrNull()
        }
    }
}
