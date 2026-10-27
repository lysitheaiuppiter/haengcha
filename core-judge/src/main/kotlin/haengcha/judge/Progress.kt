package haengcha.judge

import java.time.LocalDate
import kotlin.math.floor

/** 누적 경험치로 레벨을 정한다. Lv.0에서 시작하고 Lv.n에 필요한 누적 경험치는 50·n·(n+1)이다(임시값). */
object Levels {
    fun xpFor(level: Int): Int = 50 * level * (level + 1)

    fun levelOf(totalXp: Int): Int {
        var level = 0
        while (xpFor(level + 1) <= totalXp) level++
        return level
    }
}

/** 조선 테마와 열리는 레벨. Lv.0 소달구지에서 시작하고, 가마 Lv.4는 기획서 부록 B 문구를 따랐다. 나머지는 임시값이다. */
enum class JoseonTheme(val label: String, val unlockLevel: Int) {
    OX_CART("소달구지", 0),
    RICKSHAW("인력거", 1),
    GAMA("가마", 4),
    KKOTGAMA("꽃가마", 8),
    EOGA("어가 행렬", 12);

    companion object {
        fun unlocked(level: Int): List<JoseonTheme> = entries.filter { level >= it.unlockLevel }
    }
}

/** 하루치 주행 합계. [unbelted]는 안전띠를 매지 않고 출발한 주행이 있었는지. */
data class DayDriving(
    val date: LocalDate,
    val movingMinutes: Double,
    val distanceKm: Double,
    val harshCount: Int,
    val unbelted: Boolean = false,
) {
    companion object {
        fun of(date: LocalDate, trips: List<TripResult>) = DayDriving(
            date,
            trips.sumOf { it.movingMinutes },
            trips.sumOf { it.distanceKm },
            trips.sumOf { it.events.size },
        )
    }
}

/**
 * 안전주행 개근 규칙(임시값).
 * 하루에 [minMovingMinutes] 이상 달린 날만 센다. 운전하지 않았거나 잠깐만 달린 날은 기록을 끊지도 늘리지도 않는다.
 * 급가속·급감속은 10km마다 [allowedPer10Km]건까지 봐준다(10km 미만이면 0건).
 * 안전띠를 매지 않고 출발한 주행이 있는 날은 개근으로 세지 않는다(기록이 끊긴다).
 */
data class StreakRule(val minMovingMinutes: Double = 5.0, val allowedPer10Km: Double = 1.0)

/** 그날이 안전주행 기준을 지켰는지. 세지 않는 날이면 null. */
fun DayDriving.isSafe(rule: StreakRule = StreakRule()): Boolean? {
    if (movingMinutes < rule.minMovingMinutes) return null
    if (unbelted) return false
    return harshCount <= floor(distanceKm / 10 * rule.allowedPer10Km).toInt()
}

/** 개근 기록. [shieldsUsed]는 끊길 날을 개근 보호권으로 이어 간 횟수. */
data class Streak(val days: Int, val shieldsUsed: Int)

/**
 * 날짜순으로 정렬한 하루 합계에서 지금 이어지고 있는 안전주행 개근 일수를 센다.
 * 기준을 못 지킨 날은 보호권([shields]장)이 남아 있으면 한 장 써서 기록을 잇는다(그날은 개근 일수에 넣지 않는다).
 */
fun streakOf(days: List<DayDriving>, rule: StreakRule = StreakRule(), shields: Int = 0): Streak {
    var streak = 0
    var used = 0
    for (day in days.sortedBy { it.date }) {
        when (day.isSafe(rule)) {
            true -> streak++
            false -> if (streak > 0 && used < shields) used++ else streak = 0
            null -> Unit
        }
    }
    return Streak(streak, used)
}

fun currentStreak(days: List<DayDriving>, rule: StreakRule = StreakRule()): Int = streakOf(days, rule).days

/** 화면에 보여 줄 누적 진행 상황. [levelProgress]는 지금 레벨 안에서 다음 레벨까지 채운 비율(0~1). */
data class ProgressSummary(
    val totalXp: Int,
    val level: Int,
    val xpToNextLevel: Int,
    val levelProgress: Float,
    val todayXp: Int,
    val streak: Int,
    val themes: List<JoseonTheme>,
    val mapaeCount: Int,
    /** 오늘 끝까지 마친 감찰 횟수(통과·실패). 하루 감찰 횟수 제한에 쓴다. */
    val inspectionsToday: Int,
    /** 방문한 지역의 단골 기록, 최근에 간 곳부터. */
    val regulars: List<RegularStatus> = emptyList(),
    /** 아이 지킴이 보너스를 받은 어린이보호구역 통과 누적 횟수. */
    val guardianCount: Int = 0,
    val tripsToday: Int = 0,
    val kmToday: Double = 0.0,
    val companion: CompanionStatus = CompanionStatus(0, CompanionRule().monthlyTrips),
    /** 남은 개근 보호권(대장간 퀘스트 보상). */
    val shields: Int = 0,
    val shieldsUsed: Int = 0,
    /** 곳간에 쌓인 쌀(섬). 검약 행차로 아낀 전력량(kWh). */
    val rice: Double = 0.0,
    val ecoTrips: Int = 0,
    val journey: JourneyStatus = journey(0.0),
    /** 도착 스탬프를 찍은 명소. */
    val stamps: Set<Landmark> = emptySet(),
    val nightTrips: Int = 0,
    val weatherCalmTrips: Int = 0,
    val restCount: Int = 0,
)

/** [shieldsEarned]는 이 운전자가 대장간 퀘스트로 받은 개근 보호권 수. */
fun summarize(
    records: List<TripRecord>,
    today: LocalDate,
    rule: StreakRule = StreakRule(),
    shieldsEarned: Int = 0,
    eco: EcoRule = EcoRule(),
): ProgressSummary {
    val total = records.sumOf { it.xp }
    val level = Levels.levelOf(total)
    val from = Levels.xpFor(level)
    val to = Levels.xpFor(level + 1)
    val days = records.groupBy { it.date }.map { (date, trips) ->
        DayDriving(
            date, trips.sumOf { it.movingMinutes }, trips.sumOf { it.distanceKm }, trips.sumOf { it.harshAccel + it.harshDecel },
            unbelted = trips.any { it.belt == false },
        )
    }
    val streak = streakOf(days, rule, shieldsEarned)
    return ProgressSummary(
        totalXp = total,
        level = level,
        xpToNextLevel = to - total,
        levelProgress = (total - from).toFloat() / (to - from),
        todayXp = records.filter { it.date == today }.sumOf { it.xp },
        streak = streak.days,
        themes = JoseonTheme.unlocked(level),
        mapaeCount = records.count { it.mapae },
        inspectionsToday = records.count { it.date == today && it.inspected },
        regulars = regulars(records),
        guardianCount = records.sumOf { it.guardian },
        tripsToday = records.count { it.date == today },
        kmToday = records.filter { it.date == today }.sumOf { it.distanceKm },
        companion = companionStatus(records, today),
        shields = shieldsEarned - streak.shieldsUsed,
        shieldsUsed = streak.shieldsUsed,
        rice = records.sumOf { r -> r.kmPerKwh?.let { savedKwh(r.journeyKm, it, eco) } ?: 0.0 },
        ecoTrips = records.count { it.eco },
        journey = journey(records.sumOf { it.journeyKm }),
        stamps = records.mapNotNull { it.stamp }.toSet(),
        nightTrips = records.count { it.night },
        weatherCalmTrips = records.count { it.weatherCalm },
        restCount = records.count { it.rested },
    )
}

/**
 * 음성 안내 문구(기획서 부록 B). 앱은 문장마다 미리 녹음한 음성을 찾아 이어 재생하므로
 * 문장을 바꾸면 tools/make_voice_clips.py로 녹음도 다시 만든다.
 */
object Announcements {
    const val BOARDING = "주상전하 납시오!"
    const val MAPAE = "암행어사 출두요! 아까 10분, 차분히 잘 달리셨습니다. 마패를 하사합니다."
    const val CLOSING = "오늘도 안전하게, 행차하셨습니다."
    const val SCHOOL_ZONE = "어린이보호구역입니다. 천천히!"
    const val GUARDIAN = "아이 지킴이! 어린이보호구역을 천천히 지나셨습니다."
    const val CHARGING = "쉬어가는 행차입니다. 오늘 모은 도감을 정리해 볼까요?"
    const val COMPANION_JOINED = "이달의 동반자 거북이가 행차에 합류했습니다."
    const val ECO = "검약 행차! 아낀 기운이 곳간에 쌀로 쌓였습니다."
    const val RAIN_CALM = "빗길을 차분히 달리셨습니다."
    const val SNOW_CALM = "눈길을 차분히 달리셨습니다."
    const val NIGHT = "야행을 나섭니다. 초롱을 밝힙니다."
    const val BELT = "안전띠를 매셔야 개근으로 셉니다."
    const val REST_DUE = "오래 달리셨습니다. 역마를 갈아탈 때입니다. 쉬어 가세요."
    const val REST_AREA_AHEAD = "앞길에 휴게소가 있습니다."
    const val REST_SEARCH = "가까운 휴게소를 내비게이션에 띄웠습니다."
    const val RESTED = "역참에서 잘 쉬셨습니다. 다시 길을 나섭니다."
    const val SHIELD_USED = "개근 보호권으로 개근 기록을 지켰습니다."
    const val QUEST_DONE = "정비를 마쳤습니다. 개근 보호권을 드립니다."

    fun levelUp(level: Int) = "Lv.$level 달성!"
    fun themeUnlocked(theme: JoseonTheme) = "${theme.label} 테마가 열렸습니다."
    fun regionEntered(region: Region) = "${region.label}에 오셨습니다. ${region.theme} 테마가 켜집니다."
    fun gradeUp(region: Region, grade: Int) = "단골 등급이 올랐습니다. ${region.grades[grade]}입니다."
    fun questOpened(quest: Quest) = "대장간 퀘스트입니다. ${quest.title}."
    fun stationReached(station: Station) = "옛길 원정, ${station.label}에 이르렀습니다."
    fun stamped(landmark: Landmark) = "${landmark.label} 도착! 스탬프를 찍었습니다."

    /** 말할 수 있는 모든 문장. 녹음 파일이 빠짐없이 있는지 확인하는 데 쓴다. */
    fun all(maxLevel: Int = 20): List<String> =
        listOf(
            BOARDING, MAPAE, CLOSING, SCHOOL_ZONE, GUARDIAN, CHARGING, COMPANION_JOINED,
            ECO, RAIN_CALM, SNOW_CALM, NIGHT, BELT, REST_DUE, REST_AREA_AHEAD, REST_SEARCH, RESTED, SHIELD_USED, QUEST_DONE,
        ) +
            (1..maxLevel).map(::levelUp) +
            JoseonTheme.entries.filter { it.unlockLevel > 0 }.map(::themeUnlocked) +
            Region.entries.map(::regionEntered) +
            Region.entries.flatMap { r -> (1..r.grades.lastIndex).map { gradeUp(r, it) } } +
            Quest.entries.map(::questOpened) +
            Station.entries.filter { it != Station.HANYANG }.map(::stationReached) +
            Landmark.entries.map(::stamped)

    /**
     * 주차 후에 한 번에 이어 말할 문장들. [guardian]은 이번 주행에서 아이 지킴이 보너스를 받은 횟수,
     * [record]가 있으면 검약·빗길·명소·옛길·보호권 문장도 넣는다.
     */
    fun tripEndLines(
        trip: TripResult,
        before: ProgressSummary,
        after: ProgressSummary,
        guardian: Int = 0,
        record: TripRecord? = null,
    ): List<String> = buildList {
        if (trip.mapae) add(MAPAE)
        if (guardian > 0) add(GUARDIAN)
        if (record != null) {
            if (record.eco) add(ECO)
            if (record.weatherCalm) add(if (record.weather == Weather.SNOW) SNOW_CALM else RAIN_CALM)
            record.stamp?.let { add(stamped(it)) }
            stationsReached(before.journey.totalKm, after.journey.totalKm).forEach { add(stationReached(it)) }
            if (after.shieldsUsed > before.shieldsUsed) add(SHIELD_USED)
        }
        if (after.level > before.level) add(levelUp(after.level))
        (after.themes - before.themes.toSet()).forEach { add(themeUnlocked(it)) }
        after.regulars.forEach { now ->
            val was = before.regulars.firstOrNull { it.region == now.region }
            if (was != null && now.grade > was.grade) add(gradeUp(now.region, now.grade))
        }
        if (after.companion.joined && !before.companion.joined) add(COMPANION_JOINED)
        add(CLOSING)
    }

    fun tripEnd(trip: TripResult, before: ProgressSummary, after: ProgressSummary, guardian: Int = 0): String =
        tripEndLines(trip, before, after, guardian).joinToString(" ")
}
