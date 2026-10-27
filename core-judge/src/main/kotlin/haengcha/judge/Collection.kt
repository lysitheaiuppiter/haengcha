package haengcha.judge

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** 희귀도. 도감 타일 테두리와 축하 연출 문구에 쓴다. */
enum class Rarity(val label: String) {
    COMMON("일반"),
    RARE("희귀"),
    EPIC("영웅"),
    LEGEND("전설"),
}

/**
 * 업적과 칭호(기획서 4.5). 주행 기록에서 계산하므로 따로 저장하지 않는다. 조건 수치는 임시값이다.
 * [tier]가 높을수록 어려운 칭호이고(5는 전설), 정차 화면에는 가진 칭호 가운데 가장 높은 것을 대표 칭호로 단다.
 * [hidden] 칭호는 얻기 전까지 이름과 조건을 감춘다.
 */
enum class Title(val label: String, val emoji: String, val desc: String, val tier: Int, val hidden: Boolean = false) {
    FIRST_TRIP("첫 행차", "🐾", "첫 주행을 마침", 1),
    TRIPS_30("길 위의 선비", "📜", "주행 30번", 2),
    LI_100("백 리 나그네", "🛤️", "누적 40km(백 리)", 1),
    LI_1000("천 리 행차꾼", "🏞️", "누적 400km(천 리)", 3),
    STREAK_7("개근 선비", "📅", "안전주행 개근 7일", 2),
    STREAK_30("개근 장원급제", "🎓", "안전주행 개근 30일", 4),
    CALM_20("정체길 도사", "🧘", "평정심 구간 20번", 2),
    MAPAE_3("암행어사의 벗", "🏅", "마패 3개", 3),
    GUARDIAN_5("아이 지킴이 대장", "🧒", "어린이보호구역을 천천히 5번", 2),
    NIGHT_5("달빛 행차꾼", "🌙", "야행 5번", 2),
    RAIN_5("우중 신선", "☔", "빗길·눈길 차분 운전 5번", 2),
    ECO_10("검약 대감", "🌾", "검약 행차 10번", 3),
    RICE_10("곳간 부자", "🪙", "곳간 쌀 10섬", 3),
    REST_3("역참 단골", "🐎", "역참에서 쉬어 가기 3번", 2),
    SMITH_3("대장간 단골", "⚒️", "대장간 퀘스트 3번 완료", 2),
    STAMP_5("명소 수집가", "🏮", "명소 스탬프 5곳", 2),
    TOWN_ALL("명소 완주", "🎖️", "한 지역의 명소를 모두 방문", 3),
    REGIONS_7("팔도 유람객", "🗺️", "지역 도감 7곳", 3),
    REGULAR_TOP("고을의 자랑", "⭐", "한 지역 최고 단골 등급", 4),
    JORYEONG("새재 넘은 나그네", "⛰️", "옛길 원정 문경새재 조령관", 3),
    YEONGNAM("영남대로 완주자", "🏆", "옛길 원정 동래 도착", 4),
    SAMNAM("삼남대로 완주자", "🌾", "영남대로 다음 길, 해남 도착", 4),
    FRIDAY_5("금요일의 해방자", "🎉", "금요일 저녁(17~22시) 주행 5번", 2),
    DAWN_3("새벽 출근 장인", "🌅", "새벽 6시 전 출발 3번", 2),
    GUARDIAN_50("어린이의 수호신", "🧸", "어린이보호구역을 천천히 50번", 4),
    RICE_100("만석꾼", "💰", "곳간 쌀 100섬", 4),
    STREAK_100("백일 개근 영의정", "🏛️", "안전주행 개근 100일", 5),
    LI_10000("만 리 행차꾼", "🌏", "누적 4,000km(만 리)", 5),
    ROADS_ALL("삼대로 대장정", "🏇", "영남·삼남·관동대로 모두 완주(1,140km)", 5),
    REGIONS_ALL("방방곡곡 대장정", "🧭", "지역 도감 15곳 모두", 5),
    STAMPS_ALL("명소 도감 완성", "📚", "명소 스탬프 34곳 모두", 5),
    MAPAE_30("어사또", "🕵️", "마패 30개", 5),
    TOP_3("삼도 명예 주민", "🏯", "세 지역에서 최고 단골 등급", 5),
    RAINY_NIGHT("비 오는 밤의 나그네", "☂️", "비 오는 밤길을 급조작 없이 달림", 3, hidden = true),
    NEW_YEAR("제야의 행차", "🔔", "섣달그믐 자정 무렵에 달림", 3, hidden = true);

    val rarity: Rarity
        get() = when (tier) {
            in 0..2 -> Rarity.COMMON
            3 -> Rarity.RARE
            4 -> Rarity.EPIC
            else -> Rarity.LEGEND
        }
}

/**
 * 주행 보상 동반자(기획서 4.8). 판매하지 않고 주행으로만 합류한다. 거북이는 이달 주행 횟수로 다달이 다시 모으고,
 * 나머지는 한 번 합류하면 계속 함께한다. 운전자가 하나를 골라 정차 화면에 데리고 다닌다.
 * 전설 동반자(청룡·봉황·구미호)는 몇 달을 꾸준히 달려야 만난다.
 */
enum class Buddy(val label: String, val emoji: String, val condition: String, val joinLine: String, val rarity: Rarity = Rarity.COMMON) {
    TURTLE("거북이", "🐢", "이달 주행 10번", Announcements.COMPANION_JOINED),
    ROOSTER("부지런한 닭", "🐓", "안전주행 개근 7일", "부지런한 닭이 행차에 합류했습니다."),
    OWL("부엉이", "🦉", "야행 5번", "부엉이가 밤길 행차에 합류했습니다."),
    SQUIRREL("다람쥐", "🐿️", "곳간 쌀 5섬", "다람쥐가 곳간지기로 합류했습니다."),
    MAGPIE("까치", "🐦", "명소 스탬프 3곳", "까치가 반가운 소식을 물고 합류했습니다."),
    SAPSAL("삽살개", "🐕", "아이 지킴이 5번", "삽살개가 행차를 지키러 합류했습니다.", Rarity.RARE),
    TIGER("호랑이", "🐅", "마패 3개", "호랑이가 암행어사의 호위로 합류했습니다.", Rarity.RARE),
    DOKKAEBI("도깨비", "👹", "옛길 문경새재 넘기", "문경새재 도깨비가 행차에 합류했습니다.", Rarity.RARE),
    DRAGON("청룡", "🐉", "안전주행 개근 100일", "전설의 청룡이 구름을 타고 행차에 합류했습니다.", Rarity.LEGEND),
    PHOENIX("봉황", "🦚", "지역 도감 15곳 모두", "전설의 봉황이 날아와 행차에 합류했습니다.", Rarity.LEGEND),
    GUMIHO("구미호", "🦊", "누적 4,000km(만 리)", "전설의 구미호가 만 리 길을 따라 행차에 합류했습니다.", Rarity.LEGEND),
}

/** 기록과 진행 요약에서 가진 칭호(어려운 것부터). [shieldsEarned]는 대장간 퀘스트로 받은 보호권 수(완료 횟수). */
fun titlesOf(records: List<TripRecord>, p: ProgressSummary, shieldsEarned: Int, zone: ZoneId): List<Title> {
    fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
    val km = p.totalKm
    val newYear = records.any { r -> local(r.startEpochMs).let { (it.monthValue == 12 && it.dayOfMonth == 31 && it.hour >= 23) || (it.monthValue == 1 && it.dayOfMonth == 1 && it.hour < 1) } }
    val friday = records.count { local(it.startEpochMs).let { t -> t.dayOfWeek == DayOfWeek.FRIDAY && t.hour in 17..21 } }
    val dawn = records.count { local(it.startEpochMs).hour < 6 }
    val townDone = Region.entries.any { r -> Landmark.entries.filter { it.region == r }.all { it in p.stamps } }
    val got = buildSet {
        if (records.isNotEmpty()) add(Title.FIRST_TRIP)
        if (records.size >= 30) add(Title.TRIPS_30)
        if (km >= 40) add(Title.LI_100)
        if (km >= 400) add(Title.LI_1000)
        if (p.bestStreak >= 7) add(Title.STREAK_7)
        if (p.bestStreak >= 30) add(Title.STREAK_30)
        if (records.sumOf { it.calmSegments } >= 20) add(Title.CALM_20)
        if (p.mapaeCount >= 3) add(Title.MAPAE_3)
        if (p.guardianCount >= 5) add(Title.GUARDIAN_5)
        if (p.nightTrips >= 5) add(Title.NIGHT_5)
        if (p.weatherCalmTrips >= 5) add(Title.RAIN_5)
        if (p.ecoTrips >= 10) add(Title.ECO_10)
        if (p.rice >= 10) add(Title.RICE_10)
        if (p.restCount >= 3) add(Title.REST_3)
        if (shieldsEarned >= 3) add(Title.SMITH_3)
        if (p.stamps.size >= 5) add(Title.STAMP_5)
        if (townDone) add(Title.TOWN_ALL)
        if (p.regulars.size >= 7) add(Title.REGIONS_7)
        if (p.regulars.any { it.grade == it.region.grades.lastIndex }) add(Title.REGULAR_TOP)
        if (p.journey.totalKm >= Station.JORYEONG.totalKm) add(Title.JORYEONG)
        if (p.journey.totalKm >= Station.DONGNAE.totalKm) add(Title.YEONGNAM)
        if (p.journey.totalKm >= Station.HAENAM.totalKm) add(Title.SAMNAM)
        if (friday >= 5) add(Title.FRIDAY_5)
        if (dawn >= 3) add(Title.DAWN_3)
        if (p.guardianCount >= 50) add(Title.GUARDIAN_50)
        if (p.rice >= 100) add(Title.RICE_100)
        if (p.bestStreak >= 100) add(Title.STREAK_100)
        if (km >= 4000) add(Title.LI_10000)
        if (p.journey.totalKm >= Road.CYCLE_KM) add(Title.ROADS_ALL)
        if (p.regulars.size == Region.entries.size) add(Title.REGIONS_ALL)
        if (p.stamps.size == Landmark.entries.size) add(Title.STAMPS_ALL)
        if (p.mapaeCount >= 30) add(Title.MAPAE_30)
        if (p.regulars.count { it.grade == it.region.grades.lastIndex } >= 3) add(Title.TOP_3)
        if (records.any { it.night && it.weatherCalm }) add(Title.RAINY_NIGHT)
        if (newYear) add(Title.NEW_YEAR)
    }
    return Title.entries.filter { it in got }.sortedByDescending { it.tier }
}

/** 합류 조건까지 모은 양. */
data class Goal(val have: Double, val need: Double) {
    constructor(have: Int, need: Int) : this(have.toDouble(), need.toDouble())

    val done: Boolean get() = have >= need
    val ratio: Float get() = (have / need).coerceIn(0.0, 1.0).toFloat()
}

/** 동반자 합류 조건과 지금까지 모은 양. */
fun Buddy.goal(p: ProgressSummary): Goal = when (this) {
    Buddy.TURTLE -> Goal(p.companion.tripsThisMonth, p.companion.goal)
    Buddy.ROOSTER -> Goal(p.bestStreak, 7)
    Buddy.OWL -> Goal(p.nightTrips, 5)
    Buddy.SQUIRREL -> Goal(p.rice, 5.0)
    Buddy.MAGPIE -> Goal(p.stamps.size, 3)
    Buddy.SAPSAL -> Goal(p.guardianCount, 5)
    Buddy.TIGER -> Goal(p.mapaeCount, 3)
    Buddy.DOKKAEBI -> Goal(p.journey.totalKm, Station.JORYEONG.totalKm.toDouble())
    Buddy.DRAGON -> Goal(p.bestStreak, 100)
    Buddy.PHOENIX -> Goal(p.regulars.size, Region.entries.size)
    Buddy.GUMIHO -> Goal(p.totalKm, 4000.0)
}

/** 지금 함께할 수 있는 동반자. */
fun buddiesOf(p: ProgressSummary): Set<Buddy> = Buddy.entries.filter { it.goal(p).done }.toSet()

/** 받침이 있으면 [withFinal], 없으면 [withoutFinal](이/가, 을/를 고르기). 한글이 아니면 [withoutFinal]. */
fun String.josa(withFinal: String, withoutFinal: String): String {
    val c = lastOrNull() ?: return withoutFinal
    if (c !in '가'..'힣') return withoutFinal
    return if ((c - '가') % 28 != 0) withFinal else withoutFinal
}
